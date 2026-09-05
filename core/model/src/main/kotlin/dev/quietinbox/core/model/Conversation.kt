package dev.quietinbox.core.model

/** Identity reliability — the third user-facing dimension. */
enum class IdentityConfidence {
    /** Backed by a fixture-proven source chat id. */
    VERIFIED_SOURCE_ID,

    /** Inferred from a notification stream / shortcut; may split one chat into several. */
    INFERRED_FROM_STREAM,

    /** Only a display name was available; same-named contacts cannot be told apart. */
    UNRESOLVED,
}

data class Conversation(
    val id: Long,
    val scope: SourceScope,
    val identityKey: String,
    val identityConfidence: IdentityConfidence,
    val title: String?,
    val isGroup: Boolean?,
    val pinned: Boolean,
    val archived: Boolean,
    val lastActivityEpochMs: Long,
    val lastViewedEpochMs: Long?,
    val messageCount: Int,
    val ambiguousCount: Int,
    val summaryOnlyCount: Int,
    val lastMessagePreview: String?,
    val lastSenderName: String?,
) {
    val hasUnviewed: Boolean get() = lastViewedEpochMs == null || lastViewedEpochMs < lastActivityEpochMs
}
