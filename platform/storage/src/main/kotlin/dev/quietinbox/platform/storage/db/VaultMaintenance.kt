package dev.quietinbox.platform.storage.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown into background work that a maintenance run cancelled; a plain cancellation, never an error. */
class MaintenanceCancellation : CancellationException("cancelled by vault maintenance")

/**
 * Told, synchronously and exactly once per run, when an exclusive maintenance run starts (before
 * any worker is cancelled) and when it has ended. A `StateFlow` would conflate a fast
 * true → false pair and the listener would never see the start (round-10 finding).
 */
interface MaintenanceListener {
    suspend fun onMaintenanceStarted()
    suspend fun onMaintenanceEnded()
}

/**
 * The one gate every writer of the vault goes through (QI-SEC-003).
 *
 * - [pipelineMutex] is the capture pipeline's single-writer lock; an [exclusive] run holds it for
 *   its whole duration, so no event can be journaled or committed while the vault is being
 *   deleted or restored.
 * - Background work that touches the vault (media copies, journal replay, retention, backup
 *   export) runs inside [work]; it is refused while maintenance is active and cancelled when
 *   maintenance starts. Whole-vault writes (reset, backup import) are themselves [exclusive] runs.
 * - [active] lets the capture side rotate its generation (dropping everything queued) and record
 *   a gap for the maintenance window.
 *
 * The order in [exclusive] — flag, cancel, join, lock — is what makes the barrier complete: a
 * worker that registered before the flag flipped is in the snapshot and gets cancelled; one that
 * registered after it re-reads the flag and refuses to start.
 */
@Singleton
class VaultMaintenance @Inject constructor() {
    val pipelineMutex = Mutex()

    private val exclusiveMutex = Mutex()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    val isActive: Boolean get() = _active.value

    private val workers = HashSet<Job>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<MaintenanceListener>()

    fun addListener(listener: MaintenanceListener) {
        listeners += listener
    }

    /**
     * Runs [block] as cancellable vault work. Returns null without running it when maintenance is
     * active; throws [MaintenanceCancellation] (a [CancellationException]) when maintenance starts
     * while it runs. Structured concurrency is preserved: the block is still a child of the caller.
     */
    suspend fun <T> work(block: suspend () -> T): T? {
        if (_active.value) return null
        return coroutineScope {
            val job = coroutineContext.job
            synchronized(workers) { workers += job }
            try {
                // Re-read after registering: an exclusive run that flipped the flag between the
                // first check and the registration has already taken its snapshot without us.
                if (_active.value) null else block()
            } finally {
                synchronized(workers) { workers -= job }
            }
        }
    }

    /**
     * Stops all vault work, then runs [block] alone under the pipeline lock. Runs are serialised.
     * Listeners are called before the workers are cancelled (so the capture side has fenced its
     * queue before the lock is even requested) and again after the flag dropped.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = exclusiveMutex.withLock {
        _active.value = true
        try {
            for (l in listeners) l.onMaintenanceStarted()
            val snapshot = synchronized(workers) { workers.toList() }
            for (job in snapshot) job.cancel(MaintenanceCancellation())
            snapshot.joinAll()
            pipelineMutex.withLock { block() }
        } finally {
            _active.value = false
            for (l in listeners) l.onMaintenanceEnded()
        }
    }
}
