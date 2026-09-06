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
import dev.quietinbox.platform.storage.db.MaintenanceListener
import dev.quietinbox.platform.storage.db.VaultMaintenance
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * A notification held back until the source policy is known (QI-CAPTURE-013). Only the framework
 * object is kept — nothing is read from it — so a notification from an app the user never
 * enabled is never materialised into a snapshot. Tests enter with a pre-built snapshot instead.
 */
private class Held(
    val sbn: StatusBarNotification?,
    val captured: CapturedNotification?,
    val origin: CaptureOrigin,
    val generation: String,
    val heldAtEpochMs: Long,
) {
    val packageName: String get() = sbn?.packageName ?: captured!!.snapshot.source.packageName
}

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
    private val maintenance: VaultMaintenance,
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

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var snapshotFactory: SnapshotFactory = SnapshotFactory(bootSessionId)
    private val registry = ParserRegistry(AppParsers.all())
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()
    private val queue = Channel<Queued>(capacity = Limits.MAX_QUEUE_DEPTH)

    private val _status = MutableStateFlow(CaptureStatus(listenerState = if (listenerAccess.isGranted()) ListenerState.GRANTED_DISCONNECTED else ListenerState.NOT_GRANTED))
    val status: StateFlow<CaptureStatus> = _status

    @Volatile
    private var activeGeneration: String? = null

    /** Sources the user enabled (paused ones included; see [pausedPackages]). */
    @Volatile
    private var enabledPackages: Set<String> = emptySet()

    /** Enabled sources the user paused: nothing new is accepted, accepted events wait (like a global pause). */
    @Volatile
    private var pausedPackages: Set<String> = emptySet()

    /** False until the first source list arrived; before that nothing is filtered at offer time. */
    @Volatile
    private var sourcesLoaded: Boolean = false

    @Volatile
    private var maintenanceStartedAt: Long? = null

    /** Notifications waiting for the source policy; bounded, oldest dropped first. Guarded by itself. */
    private val held = ArrayDeque<Held>()
    private var heldDropped = 0

    /** Arrival time of the first notification the buffer had to evict; the gap starts there, not at a survivor. */
    private var heldDroppedSince: Long? = null

    @Volatile
    private var coldStartJob: Job? = null

    /** A COLD_START gap row is open; while the policy stays unknown further drops extend it instead of adding rows. */
    @Volatile
    private var coldStartGapId: Long? = null

    /**
     * Start of a cold-start loss the locked vault could not record at the time. Written as a
     * bounded gap as soon as the vault can be written again (round-12 finding: a drop while the
     * vault is locked must not vanish just because the gap table was unreachable).
     */
    @Volatile
    private var coldStartLossSince: Long? = null

    /** Same for the pipeline's own lock-out gap: the time `openGap` could not be written. */
    @Volatile
    private var vaultGapSince: Long? = null

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var listenerBound: Boolean = false

    @Volatile
    private var sessionId: Long? = null

    /**
     * Serialises live processing, journal replay and source-policy changes so one event is never
     * committed twice and no event is committed for a source the user just switched off. Shared
     * with [VaultMaintenance], which holds it for the whole of a reset or restore (QI-SEC-003).
     */
    private val pipelineMutex get() = maintenance.pipelineMutex

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
            // The emitted list is only a trigger: the policy is re-read from the vault under the
            // pipeline lock, so a stale emission can never overwrite a change made through
            // [changeSourcePolicy] (QI-SEC-001).
            sources.observeSources().catch { emit(emptyList()) }.collectLatest {
                pipelineMutex.withLock { guarded { loadSourcePolicy() } }
            }
        }
        // Explicit callbacks, not a StateFlow: a fast start → end pair would be conflated and the
        // start (generation rotation, gap) never seen.
        maintenance.addListener(object : MaintenanceListener {
            override suspend fun onMaintenanceStarted() = onMaintenance(true)
            override suspend fun onMaintenanceEnded() = onMaintenance(false)
        })
        scope.launch {
            vault.state.collectLatest { s ->
                _status.update { it.copy(vaultLocked = s is VaultState.Locked) }
                if (s is VaultState.Ready) {
                    if (vaultGapOpen) {
                        vaultGapOpen = false
                        val now = System.currentTimeMillis()
                        val since = vaultGapSince
                        vaultGapSince = null
                        guarded {
                            health.closeOpenGaps(now, GapReason.UNKNOWN)
                            // The row could not be opened while the vault was locked: recorded now, bounded.
                            if (since != null) health.recordGap(since, now, GapReason.UNKNOWN, GapPrecision.BOUNDED, now)
                        }
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
                val streamKey = identity.streamKey(SourceScope(sbn.packageName, "user:${sbn.user.hashCode()}", null), sbn.tag?.take(Limits.MAX_KEY_CHARS), sbn.id)
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
            // Accepted events waited out the pause in the journal; they are committed now.
            if (!value) replayJournal()
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
        return pkg in enabledPackages && pkg !in pausedPackages
    }

    // ---- source policy (QI-SEC-001) ----------------------------------------------------------

    /**
     * Every change to which sources are captured goes through here: the vault write and the
     * in-memory policy update happen under the pipeline lock, so an event waiting for the lock
     * sees the new policy in its second fence, never the old one.
     */
    private suspend fun changeSourcePolicy(block: suspend () -> Unit) {
        pipelineMutex.withLock {
            block()
            loadSourcePolicy()
        }
    }

    /**
     * Must be called under [pipelineMutex]. Once the policy is known, held notifications are
     * decided — *before* the flag flips, so a notification arriving during the release still goes
     * through the buffer and cannot overtake the ones held before it (CI caught the reordering).
     */
    private suspend fun loadSourcePolicy() {
        val list = sources.sources()
        enabledPackages = list.filter { it.enabled }.map { it.packageName }.toSet()
        pausedPackages = list.filter { it.enabled && it.paused }.map { it.packageName }.toSet()
        settleColdStartGap()
        releaseHeld()
        sourcesLoaded = true
        // Held in the instant before the flag flipped: released now, still in arrival order.
        if (synchronized(held) { held.isNotEmpty() }) releaseHeld()
    }

    /**
     * The policy is known and the vault writable: close the lock-out's open COLD_START row (by
     * reason, idempotent — after a restore the row survived, after a reset it is gone) and write
     * the loss the locked vault could not record at the time. Must run under [pipelineMutex].
     */
    private suspend fun settleColdStartGap() {
        val now = System.currentTimeMillis()
        coldStartGapId = null
        val since = coldStartLossSince
        coldStartLossSince = null
        guarded {
            health.closeOpenGaps(now, GapReason.COLD_START)
            if (since != null) health.recordGap(since, now, GapReason.COLD_START, GapPrecision.BOUNDED, now)
        }
    }

    // ---- cold start (QI-CAPTURE-013) ---------------------------------------------------------

    /**
     * Before the source list is known nothing is read from a third-party notification: the
     * framework object waits in a small bounded buffer. The policy is loaded right away (which
     * waits for the vault); once known, held notifications from enabled sources are snapshotted
     * and queued, all others are dropped unread. If the vault does not open in time the buffer is
     * dropped and the window is recorded as a bounded gap — fail closed, never fail open.
     */
    private fun hold(item: Held) {
        // The job check and the launch sit inside the same lock as the buffer, so two callback
        // threads cannot start two cold-start jobs and leave an item between them (round-11).
        synchronized(held) {
            if (held.size >= MAX_HELD) {
                val evicted = held.removeFirst()
                heldDropped++
                if (heldDroppedSince == null) heldDroppedSince = evicted.heldAtEpochMs
            }
            held += item
            if (coldStartJob?.isActive != true) coldStartJob = scope.launch { coldStart() }
        }
    }

    private suspend fun coldStart() {
        val loaded = withTimeoutOrNull(COLD_START_TIMEOUT_MS) {
            pipelineMutex.withLock {
                // Policy already known (another path loaded it while we were held): decide now,
                // otherwise the buffered items would sit there for good (round-11 finding).
                if (!sourcesLoaded) guarded { loadSourcePolicy() } else releaseHeld()
                sourcesLoaded
            }
        } == true
        if (!loaded) {
            dropHeld(System.currentTimeMillis())
            return
        }
        // Anything held in the instant between the release above and this job ending is released too.
        if (synchronized(held) { held.isNotEmpty() }) pipelineMutex.withLock { releaseHeld() }
    }

    /**
     * Decides every held notification against the now-known policy. Safe under the pipeline lock.
     * Notifications the bounded buffer had to drop before the policy was known are recorded as a
     * bounded gap: a dropped notification is never hidden (round-11 finding). The held objects are
     * the framework's own (`onConnected`'s resync list holds the same ones), so the bitmap bound
     * of the queue does not apply until they are snapshotted here.
     */
    private fun releaseHeld() {
        val (items, dropped, since) = synchronized(held) {
            Triple(held.toList().also { held.clear() }, heldDropped.also { heldDropped = 0 }, heldDroppedSince.also { heldDroppedSince = null })
        }
        if (dropped > 0) {
            // The evicted ones arrived before every survivor: the gap starts at the first eviction.
            val start = since ?: items.minOfOrNull { it.heldAtEpochMs }
            val now = System.currentTimeMillis()
            scope.launch { guarded { health.recordGap(start, now, GapReason.COLD_START, GapPrecision.BOUNDED, now) } }
        }
        for (h in items) {
            // Held under an older generation, or while paused: a disconnect, pause or maintenance
            // happened meanwhile, and each of those already records its own gap for that window.
            if (h.generation != activeGeneration || paused) continue
            val pkg = h.packageName
            if (!(pkg in enabledPackages && pkg !in pausedPackages)) continue
            // Observed when it arrived, not when the policy finally let it through.
            val captured = h.captured ?: runCatching { snapshotFactory.create(h.sbn!!, h.origin, h.generation, h.heldAtEpochMs) }
                .onFailure {
                    _status.update { it.copy(captureErrors = it.captureErrors + 1) }
                    lastError = it::class.java.simpleName
                }
                .getOrNull() ?: continue
            enqueue(captured, h.generation, h.heldAtEpochMs)
        }
    }

    /**
     * Drops what is held and records the loss as one open COLD_START gap per lock-out: while the
     * vault stays locked every new notification would otherwise add a row of its own (round-11
     * finding). The gap is closed when the policy finally loads.
     */
    private suspend fun dropHeld(now: Long) {
        val (items, dropped, since) = synchronized(held) {
            Triple(held.toList().also { held.clear() }, heldDropped.also { heldDropped = 0 }, heldDroppedSince.also { heldDroppedSince = null })
        }
        if (items.isEmpty() && dropped == 0) return
        if (coldStartGapId != null) return
        val start = since ?: items.minOfOrNull { it.heldAtEpochMs }
        var written = false
        guarded {
            coldStartGapId = health.openGap(start, GapReason.COLD_START, GapPrecision.BOUNDED, now)
            written = true
        }
        // A locked vault cannot take the row: remember the loss, it is written once the vault opens.
        if (!written && coldStartLossSince == null) coldStartLossSince = start ?: now
    }

    suspend fun addSource(packageName: String, displayName: String, adapterId: String?, now: Long) =
        changeSourcePolicy { sources.enable(packageName, displayName, adapterId, now) }

    /** Disabling also discards the source's pending journal rows: nothing captured for it may land later. */
    suspend fun setSourceEnabled(packageName: String, enabled: Boolean) = changeSourcePolicy {
        sources.setEnabled(packageName, enabled)
        if (!enabled) ingest.discardPendingJournal(packageName)
    }

    suspend fun setSourcePaused(packageName: String, paused: Boolean) {
        changeSourcePolicy { sources.setPaused(packageName, paused) }
        if (!paused) scope.launch { replayJournal() }
    }

    /** [SourceRepository.remove] discards the pending journal and, with [deleteData], the whole deletion graph. */
    suspend fun removeSource(packageName: String, deleteData: Boolean) =
        changeSourcePolicy { sources.remove(packageName, deleteData) }

    // ---- maintenance (QI-SEC-003) -----------------------------------------------------------

    /**
     * A reset or restore is starting or finishing. Starting rotates the generation (everything
     * queued is dropped, nothing new is queued) and ends the capture session; finishing starts a
     * fresh generation and session, records the window as an exact gap and replays whatever the
     * journal still holds. Events already inside the pipeline lock are handled by the second
     * fence, which re-reads [VaultMaintenance.isActive].
     */
    private suspend fun onMaintenance(active: Boolean) {
        val now = System.currentTimeMillis()
        if (active) {
            if (maintenanceStartedAt != null) return
            maintenanceStartedAt = now
            activeGeneration = null
            // The vault may be about to disappear: a cold-start gap that was open belongs to it.
            coldStartGapId = null
            val ended = sessionId
            sessionId = null
            // The listener state is left alone: DEGRADED reads as "queue overflow" on the health
            // page, and a reset or restore is neither a failure nor a disconnect.
            _status.update { it.copy(activeGeneration = null) }
            scope.launch { guarded { ended?.let { health.endSession(it, now, "MAINTENANCE") } } }
        } else {
            val startedAt = maintenanceStartedAt ?: return
            maintenanceStartedAt = null
            // The vault may be brand new: the policy is reloaded before the next event is admitted.
            sourcesLoaded = false
            val resumed = if (listenerBound && !paused) UUID.randomUUID().toString() else null
            activeGeneration = resumed
            _status.update {
                it.copy(
                    activeGeneration = resumed,
                    listenerState = when {
                        !listenerAccess.isGranted() -> ListenerState.NOT_GRANTED
                        paused -> ListenerState.PAUSED
                        resumed != null -> ListenerState.CONNECTED
                        else -> ListenerState.GRANTED_DISCONNECTED
                    },
                )
            }
            // Bookkeeping and replay are launched, not awaited: this runs inside the maintenance
            // caller, which must not be held hostage by a vault that is slow (or failing) to reopen.
            scope.launch {
                guarded {
                    if (resumed != null) sessionId = health.startSession(resumed, bootSessionId, now)
                    health.recordGap(startedAt, now, GapReason.MAINTENANCE, GapPrecision.EXACT, now)
                }
                replayJournal()
            }
        }
    }

    // ---- pipeline -----------------------------------------------------------------------

    private fun offer(sbn: StatusBarNotification, origin: CaptureOrigin) {
        val gen = activeGeneration ?: return
        if (paused) return
        val now = System.currentTimeMillis()
        // Own package: only marked synthetic notifications. Other packages: filtered here once the
        // source list is known; before that the framework object is held unread (QI-CAPTURE-013).
        if (sbn.packageName == context.packageName) {
            if (!isCapturable(sbn)) return
        } else if (!sourcesLoaded) {
            hold(Held(sbn, null, origin, gen, now))
            return
        } else if (!isCapturable(sbn)) {
            return
        }
        val captured = runCatching { snapshotFactory.create(sbn, if (sbn.packageName == context.packageName) CaptureOrigin.SYNTHETIC else origin, gen, now) }
            .getOrElse {
                _status.update { it.copy(captureErrors = it.captureErrors + 1) }
                lastError = it::class.java.simpleName
                return
            }
        enqueue(captured, gen, now)
    }

    /**
     * Test seam. A `StatusBarNotification` cannot be built outside the framework, so JVM tests
     * enter the pipeline with an already-built snapshot. The admission rules are the ones [offer]
     * applies, restated against the snapshot: own-package events must be synthetic, and other
     * packages are filtered here only once the source list is known.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun offerCaptured(captured: CapturedNotification) {
        val gen = activeGeneration ?: return
        if (paused) return
        val pkg = captured.snapshot.source.packageName
        val now = System.currentTimeMillis()
        if (pkg == context.packageName) {
            if (captured.snapshot.origin != CaptureOrigin.SYNTHETIC) return
        } else if (!sourcesLoaded) {
            hold(Held(null, captured, captured.snapshot.origin, gen, now))
            return
        } else if (!(pkg in enabledPackages && pkg !in pausedPackages)) {
            return
        }
        enqueue(captured, gen, now)
    }

    private fun enqueue(built: CapturedNotification, gen: String, now: Long) {
        var captured = built
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

    /**
     * Admission fence, evaluated twice: once before waiting for the pipeline lock (cheap drop)
     * and once more inside it (QI-SEC-001), because a pause, a maintenance run, a revoke or a
     * source switched off while the event waited must win. Synthetic notifications keep the
     * own-package exception. Before the source list is known nothing is filtered here; the
     * in-lock reload makes the second fence real.
     */
    private fun admitted(item: Queued): Boolean {
        val snapshot = item.captured.snapshot
        if (paused || maintenance.isActive || item.generation != activeGeneration) return false
        if (snapshot.origin == CaptureOrigin.SYNTHETIC || !sourcesLoaded) return true
        val pkg = snapshot.source.packageName
        return pkg in enabledPackages && pkg !in pausedPackages
    }

    private suspend fun process(item: Queued) {
        _status.update { it.copy(queueDepth = (it.queueDepth - 1).coerceAtLeast(0)) }
        val snapshot = item.captured.snapshot
        // The bitmap is counted until the media copy has finished with it, not until it left the queue.
        var bitmapHandedOver = false
        var journaled = false
        try {
            if (!admitted(item)) {
                _status.update { it.copy(droppedAfterRevoke = it.droppedAfterRevoke + 1) }
                return
            }
            pipelineMutex.withLock {
                try {
                    if (!sourcesLoaded) loadSourcePolicy()
                    if (!admitted(item)) {
                        _status.update { it.copy(droppedAfterRevoke = it.droppedAfterRevoke + 1) }
                        return
                    }
                    val ttl = settings.current().journalTtlHours * 60L * 60L * 1000L
                    if (!ingest.journal(snapshot, item.generation, ttl)) return
                    journaled = true
                    _status.update { it.copy(acceptedCount = it.acceptedCount + 1) }
                    bitmapHandedOver = processJournaled(snapshot, item.generation, item.captured.bitmap)
                } catch (e: VaultUnavailableException) {
                    _status.update { it.copy(vaultLocked = true, listenerState = ListenerState.DEGRADED) }
                    // The vault went away before the commit (an event journaled first is replayed later;
                    // one not journaled is lost): record an observable gap once per lock-out.
                    if (!vaultGapOpen) {
                        vaultGapOpen = true
                        var written = false
                        guarded {
                            health.openGap(snapshot.observedAtEpochMs, GapReason.UNKNOWN, GapPrecision.BOUNDED, snapshot.observedAtEpochMs)
                            written = true
                        }
                        // The gap table is behind the same lock: remembered, written when the vault opens.
                        if (!written) vaultGapSince = snapshot.observedAtEpochMs
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (journaled) {
                        guarded { ingest.markJournalRetryable(snapshot.eventId, e::class.java.simpleName) }
                    } else {
                        // The journal insert itself failed (e.g. the vault was busy): there is no row to
                        // retry, so the loss is recorded as a gap instead of vanishing (round-11 finding).
                        lastError = e::class.java.simpleName
                        guarded {
                            ingest.diagnostic("JOURNAL_FAILED", e::class.java.simpleName, snapshot.source.packageName, snapshot.observedAtEpochMs)
                            health.recordGap(snapshot.observedAtEpochMs, snapshot.observedAtEpochMs, GapReason.UNKNOWN, GapPrecision.EXACT, snapshot.observedAtEpochMs)
                        }
                    }
                }
            }
        } finally {
            if (item.captured.bitmap != null && !bitmapHandedOver) queuedBitmaps.decrementAndGet()
        }
    }

    /**
     * True when the event must not be committed now. A source disabled or removed since the
     * event was accepted discards it for good; a pause (global or per source) or a maintenance
     * run leaves it PENDING for the next replay. Synthetic notifications only honour the pause.
     */
    private suspend fun commitFenced(snapshot: NotificationSnapshot): Boolean {
        val pkg = snapshot.source.packageName
        if (snapshot.origin != CaptureOrigin.SYNTHETIC) {
            if (pkg !in enabledPackages) {
                ingest.markJournal(snapshot.eventId, "DISCARDED", "SOURCE_DISABLED")
                return true
            }
            if (pkg in pausedPackages) return true
        }
        return paused || maintenance.isActive
    }

    /**
     * Parses, reconciles and commits one journaled event. Must run under [pipelineMutex].
     * Returns true when the bitmap was handed to the media copier (which then owns its count).
     *
     * Commit fence (QI-SEC-001): a source that was disabled or removed since the event was
     * accepted discards it for good; a pause (global or per source) or a maintenance run leaves
     * it PENDING for the next replay.
     */
    private suspend fun processJournaled(snapshot: NotificationSnapshot, generation: String, bitmap: Bitmap?): Boolean {
        val now = snapshot.observedAtEpochMs
        if (commitFenced(snapshot)) return false
        val parser = registry.parserFor(snapshot)
        val batch = try {
            parser.parse(snapshot)
        } catch (e: Exception) {
            ingest.markJournal(snapshot.eventId, "FAILED", "PARSE_${e::class.java.simpleName}")
            ingest.diagnostic("PARSE_EXCEPTION", "${parser.id}@${parser.version}:${e::class.java.simpleName}", snapshot.source.packageName, now)
            return false
        }
        if (batch.messages.isEmpty() && batch.summary == null) {
            ingest.markJournal(snapshot.eventId, "SKIPPED", batch.contentStatus.name)
            ingest.diagnostic("SKIPPED_${batch.contentStatus.name}", "${parser.id}@${parser.version}", snapshot.source.packageName, now)
            return false
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
        // Re-checked right before the write: parsing and the id lookups above took time, and a
        // pause that landed meanwhile must still win (the row stays PENDING for the replay).
        if (commitFenced(snapshot)) return false
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
            scope.launch {
                try {
                    mediaCopier.copyPending(outcome.pendingMediaMessageIds, bitmap)
                } finally {
                    if (bitmap != null) queuedBitmaps.decrementAndGet()
                }
            }
            return bitmap != null
        }
        return false
    }

    /**
     * Commits what the journal still holds. Runs as vault work (refused or cancelled by a
     * maintenance run) and never while capture is paused: "stop" means nothing is written, and
     * the accepted events wait for the resume (QI-SEC-001). Each event passes the commit fence
     * in [processJournaled], so a source disabled since is discarded, not replayed.
     */
    private suspend fun replayJournal() {
        maintenance.work {
            withContext(Dispatchers.Default) {
                guarded {
                    // Drain in batches until nothing is pending (a long lock-out can leave > 200 rows).
                    var rounds = 0
                    var progressed = true
                    while (progressed && rounds++ < 100 && !paused) {
                        // Fetch and process under the pipeline mutex so a live event that was journaled
                        // but not yet committed cannot be replayed concurrently.
                        // Paused sources are excluded at the query so they cannot occupy the whole page
                        // and starve everyone else; their rows are replayed when they are unpaused.
                        val batch = ingest.pendingJournal(excludingPackages = pausedPackages)
                        if (batch.isEmpty()) break
                        progressed = false
                        for ((generation, snapshot) in batch) {
                            if (paused) break
                            // One event per lock acquisition so live capture is never starved; the
                            // PENDING re-check inside the lock prevents double processing.
                            pipelineMutex.withLock {
                                if (!sourcesLoaded) loadSourcePolicy()
                                if (!ingest.isJournalPending(snapshot.eventId)) {
                                    progressed = true
                                    return@withLock
                                }
                                val replay = snapshot.copy(origin = if (snapshot.origin == CaptureOrigin.SYNTHETIC) snapshot.origin else CaptureOrigin.REPLAY)
                                try {
                                    processJournaled(replay, generation, null)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    ingest.markJournalRetryable(snapshot.eventId, "REPLAY_${e::class.java.simpleName}")
                                }
                                // A row left PENDING on purpose (paused source, maintenance) must not spin the loop.
                                if (!ingest.isJournalPending(snapshot.eventId)) progressed = true
                            }
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

        /**
         * Notifications held unread before the source policy is known; the oldest is dropped first
         * and every drop is recorded as a gap. Sized like the queue: it carries a whole
         * active-notification resync on a cold start. Memory: these are the framework's own
         * objects (a resync list holds the same ones), each possibly carrying a BigPicture bitmap;
         * the bound is on their number, not their bytes, and they are released within 15 s.
         */
        private const val MAX_HELD = 256

        /** How long a held notification waits for the vault before it is dropped and a gap recorded. */
        private const val COLD_START_TIMEOUT_MS = 15_000L
    }
}
