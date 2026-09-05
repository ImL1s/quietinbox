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
    /**
     * Index into [ReconcileResult.decisions] when this item came from the current batch, or null
     * when it was carried over from the previous window. Lets the store map freshly inserted ids.
     */
    val decisionIndex: Int? = null,
)

/** The last known content of a notification stream, persisted as a checkpoint. */
data class MessageWindow(
    val notificationKey: String,
    val items: List<WindowItem>,
    /** True after `onNotificationRemoved` for this key. */
    val closed: Boolean,
    /** `StatusBarNotification.postTime` of the post that produced this window, when known. */
    val postedAtEpochMs: Long? = null,
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

enum class ReconcileNote { WINDOW_TRUNCATED, DEGRADED_RESOURCE_LIMIT, NO_PREVIOUS_WINDOW, FULL_OVERLAP, STALE_REPLAY, WINDOW_KEPT }

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
 * - the whole new window is aligned against the previous one by the largest suffix/prefix
 *   overlap (all items, with or without ids, so positions never drift); items after the overlap
 *   are new; a window fully contained in the previous one is a stale replay;
 * - a fixture-proven `sourceMessageId` then overrides the positional decision for that item:
 *   same id + same body = known, same id + new body = revision;
 * - a single, id-less, timestamp-less item identical to the last known item of a *closed or
 *   different* notification is ambiguous: [Decision.AmbiguousRepeat], never silently dropped and
 *   never counted as a confirmed second message;
 * - equal items inside one window keep their multiplicity;
 * - a replay that adds nothing never shrinks the checkpoint: the previous window is kept, so the
 *   next real update still aligns (plan: old content re-appearing never deletes newer content).
 */
class Reconciler(
    private val maxWindow: Int = Limits.MAX_WINDOW_ITEMS,
) {
    fun reconcile(
        notificationKey: String,
        candidates: List<MessageCandidate>,
        previous: MessageWindow?,
        lookupById: (String) -> KnownMessage?,
        postedAtEpochMs: Long? = null,
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
        val prevItems = previous?.items.orEmpty()
        val prevFps = prevItems.map { it.fingerprint }

        // 1. Positional alignment over the complete window.
        var overlap = suffixPrefixOverlap(prevFps, fps)
        var stale = false
        var staleAt = -1
        if (overlap == 0 && fps.isNotEmpty() && prevFps.isNotEmpty()) {
            staleAt = containedAt(prevFps, fps)
            if (staleAt >= 0) {
                stale = true
                overlap = fps.size
                notes += ReconcileNote.STALE_REPLAY
            }
        }
        if (fps.isNotEmpty() && overlap == fps.size) notes += ReconcileNote.FULL_OVERLAP

        // The same post re-observed (active-notification resync after a reconnect keeps the key
        // and the post time) is a repost even though the window was closed on disconnect.
        val samePost = previous != null && previous.notificationKey == notificationKey &&
            (!previous.closed || (postedAtEpochMs != null && postedAtEpochMs == previous.postedAtEpochMs))
        val decisions = ArrayList<Decision>(working.size)
        for ((i, c) in working.withIndex()) {
            val fp = fps[i]
            val positional: Decision = if (i < overlap) {
                val prevIndex = if (stale) staleAt + i else prevItems.size - overlap + i
                val existing = prevItems.getOrNull(prevIndex)?.messageId
                val hasSourceTime = c.sourceTimestampEpochMs != null && c.timestampQuality == TimestampQuality.SOURCE_MESSAGE
                val singleIdentical = fps.size == 1 && !samePost && !hasSourceTime && !stale && c.sourceMessageId == null
                if (singleIdentical) {
                    Decision.AmbiguousRepeat(c, fp, existing)
                } else {
                    Decision.Known(c, fp, existing, if (stale) KnownKind.STALE_WINDOW else KnownKind.REPOST)
                }
            } else {
                Decision.New(c, fp, confirmedById = false)
            }

            // 2. A proven source id overrides the positional decision.
            val sid = c.sourceMessageId
            val decision = if (sid == null) {
                positional
            } else {
                val known = lookupById(sid)
                when {
                    known == null && positional is Decision.New -> Decision.New(c, fp, confirmedById = true)
                    known == null -> positional
                    known.body == c.body -> Decision.Known(c, fp, known.messageId, KnownKind.SAME_ID)
                    else -> Decision.Revision(c, fp, known.messageId)
                }
            }
            decisions += decision
        }

        // 3. New window. A replay that adds nothing keeps the previous (longer) window so the
        //    next real update still aligns; otherwise the current content is the window.
        val addsNothing = decisions.none { it is Decision.New || it is Decision.Revision || it is Decision.AmbiguousRepeat }
        val window = if (addsNothing && prevItems.size > fps.size) {
            notes += ReconcileNote.WINDOW_KEPT
            MessageWindow(notificationKey, prevItems.map { it.copy(decisionIndex = null) }, closed = false, postedAtEpochMs = postedAtEpochMs ?: previous?.postedAtEpochMs)
        } else {
            val items = decisions.mapIndexed { i, d ->
                val existing = when (d) {
                    is Decision.Known -> d.existingMessageId
                    is Decision.Revision -> d.existingMessageId
                    is Decision.AmbiguousRepeat -> d.existingMessageId
                    is Decision.New -> null
                }
                WindowItem(d.fingerprint, d.candidate.sourceMessageId, existing, decisionIndex = i)
            }
            MessageWindow(notificationKey, items.takeLast(maxWindow), closed = false, postedAtEpochMs = postedAtEpochMs)
        }
        return ReconcileResult(decisions, window, notes)
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
