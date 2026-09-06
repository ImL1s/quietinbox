package dev.quietinbox.platform.storage.repo

/**
 * Decides whether a candidate that carries the fingerprint of a deleted message *is* that message
 * coming back (suppress) or a later, genuinely new message with the same text (store), QI-DEDUP-009.
 *
 * - Both sides have a proven source id and they agree: suppress.
 * - Otherwise the post times decide: a notification posted at or before the deleted message's post
 *   is a replay of it; one posted later is new. Without a post time on either side the token
 *   applies — the old, conservative behaviour.
 *
 * Two ids that *differ* do not clear the candidate on their own: a token is keyed by
 * `(scope, fingerprint)`, so when several deleted messages shared one fingerprint (same sender,
 * same text, no per-message timestamp) the token remembers only the last id, and a replay of the
 * whole post would otherwise bring the others back (round-11 finding). Post time decides then.
 *
 * Residual limitation (documented): an app that re-posts the same content with a *new* post time
 * after a reboot is indistinguishable from a new message without ids, so it comes back — and,
 * for the reason above, a genuinely new message with the same fingerprint inside the *same*
 * post as the deleted one is suppressed too.
 */
object SuppressionRule {
    fun applies(tokenSourceId: String?, tokenPostedAtEpochMs: Long?, candidateSourceId: String?, postedAtEpochMs: Long?): Boolean = when {
        tokenSourceId != null && candidateSourceId != null && tokenSourceId == candidateSourceId -> true
        tokenPostedAtEpochMs == null || postedAtEpochMs == null -> true
        else -> postedAtEpochMs <= tokenPostedAtEpochMs
    }
}
