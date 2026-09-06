package dev.quietinbox.platform.storage.repo

/**
 * Decides whether a candidate that carries the fingerprint of a deleted message *is* that message
 * coming back (suppress) or a later, genuinely new message with the same text (store), QI-DEDUP-009.
 *
 * - Both sides have a proven source id: the ids decide.
 * - Otherwise the post times decide: a notification posted at or before the deleted message's post
 *   is a replay of it; one posted later is new. Without a post time on either side the token
 *   applies — the old, conservative behaviour.
 *
 * Residual limitation (documented): an app that re-posts the same content with a *new* post time
 * after a reboot is indistinguishable from a new message without ids, so it comes back.
 */
object SuppressionRule {
    fun applies(tokenSourceId: String?, tokenPostedAtEpochMs: Long?, candidateSourceId: String?, postedAtEpochMs: Long?): Boolean = when {
        tokenSourceId != null && candidateSourceId != null -> tokenSourceId == candidateSourceId
        tokenPostedAtEpochMs == null || postedAtEpochMs == null -> true
        else -> postedAtEpochMs <= tokenPostedAtEpochMs
    }
}
