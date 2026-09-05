package dev.quietinbox.platform.capture

import android.content.Context
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.model.Limits
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.core.parser.ParserRegistry
import dev.quietinbox.core.reconcile.KnownMessage
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.parsers.apps.AppParsers
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.db.VaultUnavailableException
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory part of capture health; persisted gaps live in [HealthRepository]. */
data class CaptureStatus(
    val listenerState: ListenerState = ListenerState.NOT_GRANTED,
    val connectedSinceEpochMs: Long? = null,
    val lastEventAtEpochMs: Long? = null,
    val queueDepth: Int = 0,
    val overflowCount: Long = 0,
    val acceptedCount: Long = 0,
    val droppedAfterRevoke: Long = 0,
    /** Snapshot construction failures (malformed extras); counted, never fatal. */
    val captureErrors: Long = 0,
    val vaultLocked: Boolean = false,
    val activeGeneration: String? = null,
    val pausedByUser: Boolean = false,
)

private class Queued(val captured: CapturedNotification, val generation: String)

/**
 * Owns the capture epoch (generation token), the bounded queue and the pipeline
 * journal -> parse -> identity -> reconcile -> commit -> media. The listener service is a thin
 * shell around this singleton so process restarts and rebinds are handled in one place.
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ingest: IngestRepository,
    private val sources: SourceRepository,
    private val health: HealthRepository,
    private val settings: SettingsRepository,
    private val vault: VaultRepository,
    private val mediaCopier: MediaCopier,
    private val listenerAccess: ListenerAccess,
) {
    /** Pipeline failures must never crash the process: they are recorded as diagnostics instead. */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        _status.update { it.copy(listenerState = if (it.listenerState == ListenerState.CONNECTED) ListenerState.DEGRADED else it.listenerState) }
        lastError = e::class.java.simpleName
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    @Volatile
    var lastError: String? = null
        private set

    private val bootSessionId = UUID.randomUUID().toString()
    private val snapshotFactory = SnapshotFactory(bootSessionId)
    private val registry = ParserRegistry(AppParsers.all())
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()
    private val queue = Channel<Queued>(capacity = Limits.MAX_QUEUE_DEPTH)

    private val _status = MutableStateFlow(CaptureStatus(listenerState = if (listenerAccess.isGranted()) ListenerState.GRANTED_DISCONNECTED else ListenerState.NOT_GRANTED))
    val status: StateFlow<CaptureStatus> = _status

    @Volatile
    private var activeGeneration: String? = null

    @Volatile
    private var enabledPackages: Set<String> = emptySet()

    /** False until the first source list arrived; before that nothing is filtered at offer time. */
    @Volatile
    private var sourcesLoaded: Boolean = false

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var listenerBound: Boolean = false

    @Volatile
    private var sessionId: Long? = null

    /** Serialises live processing and journal replay so one event is never committed twice. */
    private val pipelineMutex = Mutex()

    /** Bitmaps waiting in the queue; bounded so a burst of BigPicture notifications cannot OOM. */
    private val queuedBitmaps = AtomicInteger(0)

    @Volatile
    private var vaultGapOpen: Boolean = false

    init {
        // Consumer loop restarts itself after any throwable (including OOM from a huge bitmap).
        scope.launch {
            while (true) {
                try {
                    for (item in queue) process(item)
                    break
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t::class.java.simpleName
                    _status.update { it.copy(listenerState = if (it.listenerState == ListenerState.CONNECTED) ListenerState.DEGRADED else it.listenerState) }
                }
            }
        }
        scope.launch {
            sources.observeSources().catch { emit(emptyList()) }.collectLatest { list ->
                enabledPackages = list.filter { it.enabled && !it.paused }.map { it.packageName }.toSet()
                sourcesLoaded = true
            }
        }
        scope.launch {
            vault.state.collectLatest { s ->
                _status.update { it.copy(vaultLocked = s is VaultState.Locked) }
                if (s is VaultState.Ready) {
                    if (vaultGapOpen) {
                        vaultGapOpen = false
                        guarded { health.closeOpenGaps(System.currentTimeMillis(), GapReason.UNKNOWN) }
                    }
                    replayJournal()
                }
            }
        }
    }

    // ---- listener callbacks -------------------------------------------------------------

    fun onConnected(service: NotificationListenerService) {
        val now = System.currentTimeMillis()
        val generation = UUID.randomUUID().toString()
        listenerBound = true
        activeGeneration = if (paused) null else generation
        _status.update {
            it.copy(
                listenerState = if (paused) ListenerState.PAUSED else ListenerState.CONNECTED,
                connectedSinceEpochMs = now,
                activeGeneration = if (paused) null else generation,
            )
        }
        val resync = runCatching { service.activeNotifications?.toList() }.getOrNull().orEmpty()
        // One coroutine: windows must be closed before the active-notification resync is queued.
        scope.launch {
            guarded {
                sessionId = health.startSession(generation, bootSessionId, now)
                health.closeOpenGaps(now, GapReason.LISTENER_DISCONNECTED, GapReason.NOT_GRANTED, GapReason.PROCESS_RESTART)
                ingest.closeAllWindows(now)
            }
            if (settings.current().captureActiveOnConnect) {
                for (sbn in resync) offer(sbn, CaptureOrigin.ACTIVE_RESYNC)
            }
        }
    }

    fun onDisconnected() {
        val now = System.currentTimeMillis()
        val gen = activeGeneration
        activeGeneration = null
        listenerBound = false
        _status.update { it.copy(listenerState = if (listenerAccess.isGranted()) ListenerState.RECONNECTING else ListenerState.NOT_GRANTED, activeGeneration = null) }
        // Cleared synchronously so a reconnect that starts a new session cannot be wiped by this
        // coroutine, and a failing endSession cannot leave the old id behind.
        val endedSession = sessionId
        sessionId = null
        scope.launch {
            guarded {
                endedSession?.let { health.endSession(it, now, "DISCONNECTED") }
                health.openGap(now, if (listenerAccess.isGranted()) GapReason.LISTENER_DISCONNECTED else GapReason.NOT_GRANTED, GapPrecision.BOUNDED, now)
                ingest.closeAllWindows(now)
            }
        }
        if (gen != null && listenerAccess.isGranted()) listenerAccess.requestRebind()
    }

    fun onPosted(sbn: StatusBarNotification) = offer(sbn, CaptureOrigin.LIVE)

    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        val now = System.currentTimeMillis()
        if (!isCapturable(sbn)) return
        scope.launch {
            guarded {
                val streamKey = identity.streamKey(SourceScope(sbn.packageName, "user:${sbn.user.hashCode()}", null), sbn.tag, sbn.id)
                ingest.closeWindow(streamKey, now)
                if (reason == REASON_LOCKDOWN) ingest.diagnostic("LOCKDOWN_REMOVAL", null, sbn.packageName, now)
            }
        }
    }

    // ---- user controls ------------------------------------------------------------------

    fun setPaused(value: Boolean) {
        paused = value
        val now = System.currentTimeMillis()
        // Commit fence: pausing rotates the generation so anything already queued is discarded;
        // resuming while still bound starts a fresh generation (and a matching capture session).
        val resumedGeneration = if (!value && listenerBound) UUID.randomUUID().toString() else null
        activeGeneration = if (value) null else resumedGeneration
        scope.launch {
            guarded {
                if (value) {
                    sessionId?.let { health.endSession(it, now, "PAUSED") }
                    sessionId = null
                } else if (resumedGeneration != null) {
                    sessionId = health.startSession(resumedGeneration, bootSessionId, now)
                }
            }
        }
        _status.update {
            it.copy(
                pausedByUser = value,
                listenerState = when {
                    !listenerAccess.isGranted() -> ListenerState.NOT_GRANTED
                    value -> ListenerState.PAUSED
                    activeGeneration != null -> ListenerState.CONNECTED
                    else -> ListenerState.GRANTED_DISCONNECTED
                },
                activeGeneration = activeGeneration,
            )
        }
        scope.launch {
            guarded {
                if (value) health.openGap(now, GapReason.PAUSED_BY_USER, GapPrecision.EXACT, now) else health.closeOpenGaps(now, GapReason.PAUSED_BY_USER)
            }
        }
    }

    fun refreshPermissionState() {
        _status.update {
            it.copy(
                listenerState = when {
                    !listenerAccess.isGranted() -> ListenerState.NOT_GRANTED
                    paused -> ListenerState.PAUSED
                    activeGeneration != null -> ListenerState.CONNECTED
                    else -> ListenerState.GRANTED_DISCONNECTED
                },
            )
        }
        if (listenerAccess.isGranted() && activeGeneration == null) listenerAccess.requestRebind()
    }

    /** Called from a synthetic publisher so a test notification is accepted even if unlisted. */
    fun isCapturable(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (pkg == context.packageName) {
            return sbn.notification.extras?.getBoolean(SyntheticNotifications.EXTRA_SYNTHETIC, false) == true
        }
        return pkg in enabledPackages
    }

    // ---- pipeline -----------------------------------------------------------------------

    private fun offer(sbn: StatusBarNotification, origin: CaptureOrigin) {
        val gen = activeGeneration ?: return
        if (paused) return
        // Own package: only marked synthetic notifications. Other packages: filter here only once
        // the source list is known; before that, queue and let process() decide (cold-start events
        // must not be silently dropped while the vault is still opening).
        if (sbn.packageName == context.packageName) {
            if (!isCapturable(sbn)) return
        } else if (sourcesLoaded && !isCapturable(sbn)) {
            return
        }
        val now = System.currentTimeMillis()
        var captured = runCatching { snapshotFactory.create(sbn, if (sbn.packageName == context.packageName) CaptureOrigin.SYNTHETIC else origin, gen, now) }
            .getOrElse {
                _status.update { it.copy(captureErrors = it.captureErrors + 1) }
                lastError = it::class.java.simpleName
                return
            }
        if (captured.bitmap != null) {
            // Keep at most a few bitmaps in flight; later ones fall back to a placeholder state.
            if (queuedBitmaps.incrementAndGet() > MAX_QUEUED_BITMAPS) {
                queuedBitmaps.decrementAndGet()
                captured = CapturedNotification(captured.snapshot, null)
            }
        }
        val ok = queue.trySend(Queued(captured, gen)).isSuccess
        if (!ok && captured.bitmap != null) queuedBitmaps.decrementAndGet()
        _status.update {
            if (ok) {
                it.copy(queueDepth = it.queueDepth + 1, lastEventAtEpochMs = now)
            } else {
                it.copy(overflowCount = it.overflowCount + 1, listenerState = ListenerState.DEGRADED)
            }
        }
        if (!ok) scope.launch { guarded { health.recordGap(now, now, GapReason.QUEUE_OVERFLOW, GapPrecision.EXACT, now) } }
    }

    private suspend fun process(item: Queued) {
        _status.update { it.copy(queueDepth = (it.queueDepth - 1).coerceAtLeast(0)) }
        if (item.captured.bitmap != null) queuedBitmaps.decrementAndGet()
        val snapshot = item.captured.snapshot
        // Commit fence: anything queued before a revoke/pause, or from a source disabled since,
        // is discarded, never persisted. Synthetic notifications keep the own-package exception.
        val stillCapturable = !sourcesLoaded || snapshot.source.packageName in enabledPackages || snapshot.origin == CaptureOrigin.SYNTHETIC
        if (paused || item.generation != activeGeneration || !stillCapturable) {
            _status.update { it.copy(droppedAfterRevoke = it.droppedAfterRevoke + 1) }
            return
        }
        pipelineMutex.withLock {
            try {
                if (!sourcesLoaded) {
                    enabledPackages = sources.sources().filter { it.enabled && !it.paused }.map { it.packageName }.toSet()
                    sourcesLoaded = true
                    if (!(snapshot.source.packageName in enabledPackages || snapshot.origin == CaptureOrigin.SYNTHETIC)) return
                }
                val ttl = settings.current().journalTtlHours * 60L * 60L * 1000L
                if (!ingest.journal(snapshot, item.generation, ttl)) return
                _status.update { it.copy(acceptedCount = it.acceptedCount + 1) }
                processJournaled(snapshot, item.generation, item.captured.bitmap)
            } catch (e: VaultUnavailableException) {
                _status.update { it.copy(vaultLocked = true, listenerState = ListenerState.DEGRADED) }
                // The vault went away before the commit (an event journaled first is replayed later;
                // one not journaled is lost): record an observable gap once per lock-out.
                if (!vaultGapOpen) {
                    vaultGapOpen = true
                    guarded { health.openGap(snapshot.observedAtEpochMs, GapReason.UNKNOWN, GapPrecision.BOUNDED, snapshot.observedAtEpochMs) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                guarded { ingest.markJournalRetryable(snapshot.eventId, e::class.java.simpleName) }
            }
        }
    }

    private suspend fun processJournaled(snapshot: NotificationSnapshot, generation: String, bitmap: Bitmap?) {
        val now = snapshot.observedAtEpochMs
        val parser = registry.parserFor(snapshot)
        val batch = try {
            parser.parse(snapshot)
        } catch (e: Exception) {
            ingest.markJournal(snapshot.eventId, "FAILED", "PARSE_${e::class.java.simpleName}")
            ingest.diagnostic("PARSE_EXCEPTION", "${parser.id}@${parser.version}:${e::class.java.simpleName}", snapshot.source.packageName, now)
            return
        }
        if (batch.messages.isEmpty() && batch.summary == null) {
            ingest.markJournal(snapshot.eventId, "SKIPPED", batch.contentStatus.name)
            ingest.diagnostic("SKIPPED_${batch.contentStatus.name}", "${parser.id}@${parser.version}", snapshot.source.packageName, now)
            return
        }
        val id = if (batch.messages.isNotEmpty()) identity.resolve(snapshot, batch) else null
        val reconcile = id?.let { ident ->
            val previous = ingest.checkpoint(ident.streamKey)
            val conversationId = ingest.findConversationId(ident)
            val known = HashMap<String, KnownMessage?>()
            for (c in batch.messages) {
                val sid = c.sourceMessageId ?: continue
                if (sid !in known) known[sid] = ingest.lookupById(conversationId, sid)
            }
            reconciler.reconcile(snapshot.notificationKey, batch.messages, previous, { known[it] }, snapshot.postedAtEpochMs)
        }
        val source = sources.get(snapshot.source.packageName)
        val appSettings = settings.current()
        val retentionDays = source?.retentionDays ?: appSettings.retentionDays
        val outcome = ingest.commit(
            snapshot = snapshot,
            batch = batch,
            identity = id,
            reconcile = reconcile,
            generation = generation,
            retentionMs = retentionDays * DAY_MS,
            mediaAllowed = appSettings.mediaCopyEnabled && (source?.mediaEnabled ?: true),
        )
        if (reconcile?.degraded == true) ingest.diagnostic("RECONCILE_DEGRADED", null, snapshot.source.packageName, now)
        if (batch.warnings.isNotEmpty()) ingest.diagnostic("PARSE_WARNINGS", batch.warnings.joinToString(",") { it.name }, snapshot.source.packageName, now)
        if (outcome.pendingMediaMessageIds.isNotEmpty()) {
            scope.launch { mediaCopier.copyPending(outcome.pendingMediaMessageIds, bitmap) }
        }
    }

    private suspend fun replayJournal() = withContext(Dispatchers.Default) {
        guarded {
            // Drain in batches until nothing is pending (a long lock-out can leave > 200 rows).
            var rounds = 0
            while (rounds++ < 100) {
                // Fetch and process under the pipeline mutex so a live event that was journaled
                // but not yet committed cannot be replayed concurrently.
                val batch = ingest.pendingJournal()
                if (batch.isEmpty()) break
                for ((generation, snapshot) in batch) {
                    // One event per lock acquisition so live capture is never starved; the
                    // PENDING re-check inside the lock prevents double processing.
                    pipelineMutex.withLock {
                        if (!ingest.isJournalPending(snapshot.eventId)) return@withLock
                        val replay = snapshot.copy(origin = if (snapshot.origin == CaptureOrigin.SYNTHETIC) snapshot.origin else CaptureOrigin.REPLAY)
                        try {
                            processJournaled(replay, generation, null)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            ingest.markJournalRetryable(snapshot.eventId, "REPLAY_${e::class.java.simpleName}")
                        }
                    }
                }
            }
        }
    }

    /** Best-effort bookkeeping: failures are swallowed, a coroutine cancellation never is. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val REASON_LOCKDOWN = 20 // NotificationListenerService.REASON_LOCKDOWN (API 29)
        private const val MAX_QUEUED_BITMAPS = 8
    }
}
