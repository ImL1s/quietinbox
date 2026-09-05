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
import kotlinx.coroutines.withContext
import java.util.UUID
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

    @Volatile
    private var paused: Boolean = false

    private var sessionId: Long? = null

    init {
        scope.launch { for (item in queue) process(item) }
        scope.launch {
            sources.observeSources().catch { emit(emptyList()) }.collectLatest { list ->
                enabledPackages = list.filter { it.enabled && !it.paused }.map { it.packageName }.toSet()
            }
        }
        scope.launch {
            vault.state.collectLatest { s ->
                _status.update { it.copy(vaultLocked = s is VaultState.Locked) }
                if (s is VaultState.Ready) replayJournal()
            }
        }
    }

    // ---- listener callbacks -------------------------------------------------------------

    fun onConnected(service: NotificationListenerService) {
        val now = System.currentTimeMillis()
        val generation = UUID.randomUUID().toString()
        activeGeneration = generation
        _status.update {
            it.copy(
                listenerState = if (paused) ListenerState.PAUSED else ListenerState.CONNECTED,
                connectedSinceEpochMs = now,
                activeGeneration = generation,
            )
        }
        scope.launch {
            runCatching {
                sessionId = health.startSession(generation, bootSessionId, now)
                health.closeOpenGap(now)
                ingest.closeAllWindows(now)
            }
        }
        val resync = runCatching { service.activeNotifications?.toList() }.getOrNull().orEmpty()
        scope.launch {
            if (settings.current().captureActiveOnConnect) {
                for (sbn in resync) offer(sbn, CaptureOrigin.ACTIVE_RESYNC)
            }
        }
    }

    fun onDisconnected() {
        val now = System.currentTimeMillis()
        val gen = activeGeneration
        activeGeneration = null
        _status.update { it.copy(listenerState = if (listenerAccess.isGranted()) ListenerState.RECONNECTING else ListenerState.NOT_GRANTED, activeGeneration = null) }
        scope.launch {
            runCatching {
                sessionId?.let { health.endSession(it, now, "DISCONNECTED") }
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
            runCatching {
                val streamKey = identity.streamKey(snapshotFactory.create(sbn, CaptureOrigin.LIVE, activeGeneration ?: "", now).snapshot)
                ingest.closeWindow(streamKey, now)
                if (reason == REASON_LOCKDOWN) ingest.diagnostic("LOCKDOWN_REMOVAL", null, sbn.packageName, now)
            }
        }
    }

    // ---- user controls ------------------------------------------------------------------

    fun setPaused(value: Boolean) {
        paused = value
        val now = System.currentTimeMillis()
        _status.update {
            it.copy(
                pausedByUser = value,
                listenerState = when {
                    !listenerAccess.isGranted() -> ListenerState.NOT_GRANTED
                    value -> ListenerState.PAUSED
                    activeGeneration != null -> ListenerState.CONNECTED
                    else -> ListenerState.GRANTED_DISCONNECTED
                },
            )
        }
        scope.launch {
            runCatching {
                if (value) health.openGap(now, GapReason.PAUSED_BY_USER, GapPrecision.EXACT, now) else health.closeOpenGap(now)
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
        if (!isCapturable(sbn)) return
        val now = System.currentTimeMillis()
        val captured = runCatching { snapshotFactory.create(sbn, if (sbn.packageName == context.packageName) CaptureOrigin.SYNTHETIC else origin, gen, now) }
            .getOrElse { return }
        val ok = queue.trySend(Queued(captured, gen)).isSuccess
        _status.update {
            if (ok) {
                it.copy(queueDepth = it.queueDepth + 1, lastEventAtEpochMs = now)
            } else {
                it.copy(overflowCount = it.overflowCount + 1, listenerState = ListenerState.DEGRADED)
            }
        }
        if (!ok) scope.launch { runCatching { health.recordGap(now, now, GapReason.QUEUE_OVERFLOW, GapPrecision.EXACT, now) } }
    }

    private suspend fun process(item: Queued) {
        _status.update { it.copy(queueDepth = (it.queueDepth - 1).coerceAtLeast(0)) }
        // Commit fence: anything queued before a revoke/pause is discarded, never persisted.
        if (item.generation != activeGeneration) {
            _status.update { it.copy(droppedAfterRevoke = it.droppedAfterRevoke + 1) }
            return
        }
        val snapshot = item.captured.snapshot
        try {
            val ttl = settings.current().journalTtlHours * 60L * 60L * 1000L
            if (!ingest.journal(snapshot, item.generation, ttl)) return
            _status.update { it.copy(acceptedCount = it.acceptedCount + 1) }
            processJournaled(snapshot, item.generation, item.captured.bitmap)
        } catch (e: VaultUnavailableException) {
            _status.update { it.copy(vaultLocked = true, listenerState = ListenerState.DEGRADED) }
        } catch (e: Exception) {
            runCatching { ingest.markJournal(snapshot.eventId, "FAILED", e::class.java.simpleName) }
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
            reconciler.reconcile(snapshot.notificationKey, batch.messages, previous) { known[it] }
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
        runCatching {
            for ((generation, snapshot) in ingest.pendingJournal()) {
                val replay = snapshot.copy(origin = if (snapshot.origin == CaptureOrigin.SYNTHETIC) snapshot.origin else CaptureOrigin.REPLAY)
                try {
                    processJournaled(replay, generation, null)
                } catch (e: Exception) {
                    ingest.markJournal(snapshot.eventId, "FAILED", "REPLAY_${e::class.java.simpleName}")
                }
            }
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val REASON_LOCKDOWN = 20 // NotificationListenerService.REASON_LOCKDOWN (API 29)
    }
}
