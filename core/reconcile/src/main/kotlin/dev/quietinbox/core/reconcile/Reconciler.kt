package dev.quietinbox.core.reconcile

import dev.quietinbox.core.model.Limits
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.TimestampQuality

/** One item of the persisted reconciliation window (the previous visible notification content). */
data class WindowItem(
    val fingerprint: String,
    val sourceMessageId: String?,
    /** Persisted message id, when this item was stored as a message. */
    val messageId: Long?,
)

/** The last known content of a notification stream, persisted as a checkpoint. */
data class MessageWindow(
    val notificationKey: String,
    val items: List<WindowItem>,
    /** True after `onNotificationRemoved` for this key. */
    val closed: Boolean,
)

/** What the reconciler knows about a previously stored message with a source id. */
data class KnownMessage(val messageId: Long, val fingerprint: String, val body: String)

enum class KnownKind {
    /** Same notification key re-posted with identical content (icon / read-state refresh). */
    REPOST,

    /** Older, already stored content arrived again (e.g. a stale window). */
    STALE_WINDOW,

    /** Matched by a proven source message id with the same body. */
    SAME_ID,
}

sealed interface Decision {
    val candidate: MessageCandidate
    val fingerprint: String

    data class New(
        override val candidate: MessageCandidate,
        override val fingerprint: String,
        val confirmedById: Boolean,
    ) : Decision

    data class Known(
        override val candidate: MessageCandidate,
        override val fingerprint: String,
        val existingMessageId: Long?,
        val kind: KnownKind,
    ) : Decision

    data class Revision(
        override val candidate: MessageCandidate,
        override val fingerprint: String,
        val existingMessageId: Long,
    ) : Decision

    data class AmbiguousRepeat(
        override val candidate: MessageCandidate,
        override val fingerprint: String,
        val existingMessageId: Long?,
    ) : Decision
}

enum class ReconcileNote { WINDOW_TRUNCATED, DEGRADED_RESOURCE_LIMIT, NO_PREVIOUS_WINDOW, FULL_OVERLAP, STALE_REPLAY }

data class ReconcileResult(
    val decisions: List<Decision>,
    /** The window to persist as the new checkpoint for this stream. */
    val newWindow: MessageWindow,
    val notes: Set<ReconcileNote>,
) {
    val degraded: Boolean get() = ReconcileNote.DEGRADED_RESOURCE_LIMIT in notes
}

/**
 * Bounded, deterministic deduplication over notification windows.
 *
 * Rules (see plan section 7.2):
 * - a fixture-proven `sourceMessageId` decides identity; same id + new body = revision;
 * - otherwise align the new window against the previous one (suffix/prefix overlap);
 *   items after the overlap are new; items inside a fully-contained stale window are known;
 * - a single, id-less, timestamp-less item identical to the last known item of a *closed or
 *   different* notification is ambiguous: it is recorded as [Decision.AmbiguousRepeat], never
 *   silently dropped and never counted as a confirmed second message;
 * - equal items inside one window keep their multiplicity;
 * - old content re-appearing never deletes newer stored content (this class never deletes).
 */
class Reconciler(
    private val maxWindow: Int = Limits.MAX_WINDOW_ITEMS,
) {
    fun reconcile(
        notificationKey: String,
        candidates: List<MessageCandidate>,
        previous: MessageWindow?,
        lookupById: (String) -> KnownMessage?,
    ): ReconcileResult {
        val notes = LinkedHashSet<ReconcileNote>()
        if (previous == null) notes += ReconcileNote.NO_PREVIOUS_WINDOW

        var working = candidates
        if (working.size > maxWindow) {
            notes += ReconcileNote.DEGRADED_RESOURCE_LIMIT
            notes += ReconcileNote.WINDOW_TRUNCATED
            working = working.takeLast(maxWindow)
        }
        val fps = working.map { Fingerprint.of(it) }

        // 1. id path
        val decisions = arrayOfNulls<Decision>(working.size)
        for ((i, c) in working.withIndex()) {
            val sid = c.sourceMessageId ?: continue
            val known = lookupById(sid)
            decisions[i] = when {
                known == null -> Decision.New(c, fps[i], confirmedById = true)
                known.body == c.body -> Decision.Known(c, fps[i], known.messageId, KnownKind.SAME_ID)
                else -> Decision.Revision(c, fps[i], known.messageId)
            }
        }

        // 2. window alignment for id-less items
        val prevItems = previous?.items.orEmpty()
        val prevFps = prevItems.map { it.fingerprint }
        val idless = working.indices.filter { decisions[it] == null }

        if (idless.isNotEmpty()) {
            val newFps = idless.map { fps[it] }
            var overlap = suffixPrefixOverlap(prevFps, newFps)
            var stale = false
            var staleAt = -1
            if (overlap == 0 && prevFps.isNotEmpty()) {
                staleAt = containedAt(prevFps, newFps)
                if (staleAt >= 0) {
                    stale = true
                    overlap = newFps.size
                    notes += ReconcileNote.STALE_REPLAY
                }
            }
            if (overlap == newFps.size) notes += ReconcileNote.FULL_OVERLAP

            val samePost = previous != null && previous.notificationKey == notificationKey && !previous.closed
            for ((k, idx) in idless.withIndex()) {
                val c = working[idx]
                val fp = fps[idx]
                if (k < overlap) {
                    val existing = if (stale) {
                        prevItems.getOrNull(staleAt + k)?.messageId
                    } else {
                        prevItems.getOrNull(prevItems.size - overlap + k)?.messageId
                    }
                    val hasSourceTime = c.sourceTimestampEpochMs != null && c.timestampQuality == TimestampQuality.SOURCE_MESSAGE
                    val singleIdentical = newFps.size == 1 && !samePost && !hasSourceTime && !stale
                    decisions[idx] = if (singleIdentical) {
                        Decision.AmbiguousRepeat(c, fp, existing)
                    } else {
                        Decision.Known(c, fp, existing, if (stale) KnownKind.STALE_WINDOW else KnownKind.REPOST)
                    }
                } else {
                    decisions[idx] = Decision.New(c, fp, confirmedById = false)
                }
            }
        }

        val finalDecisions = decisions.map { requireNotNull(it) }

        // 3. new window
        val newItems = finalDecisions.map { d ->
            val existing = when (d) {
                is Decision.Known -> d.existingMessageId
                is Decision.Revision -> d.existingMessageId
                is Decision.AmbiguousRepeat -> d.existingMessageId
                is Decision.New -> null
            }
            WindowItem(d.fingerprint, d.candidate.sourceMessageId, existing)
        }
        val window = MessageWindow(notificationKey, newItems.takeLast(maxWindow), closed = false)
        return ReconcileResult(finalDecisions, window, notes)
    }

    /** Largest k such that prev.takeLast(k) == next.take(k). */
    internal fun suffixPrefixOverlap(prev: List<String>, next: List<String>): Int {
        val max = minOf(prev.size, next.size)
        for (k in max downTo 1) {
            var ok = true
            val off = prev.size - k
            for (i in 0 until k) {
                if (prev[off + i] != next[i]) {
                    ok = false
                    break
                }
            }
            if (ok) return k
        }
        return 0
    }

    /** Index at which [next] appears contiguously inside [prev], or -1. */
    internal fun containedAt(prev: List<String>, next: List<String>): Int {
        if (next.isEmpty() || next.size > prev.size) return -1
        for (start in 0..prev.size - next.size) {
            var match = true
            for (i in next.indices) {
                if (prev[start + i] != next[i]) {
                    match = false
                    break
                }
            }
            if (match) return start
        }
        return -1
    }
}
