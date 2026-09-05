package dev.quietinbox.core.model

/** Deduplication outcome for a stored message. */
enum class DedupState {
    /** Distinct message, or matched by a proven source id. */
    CONFIRMED,

    /** Observed via window alignment without a source id. */
    CANDIDATE,

    /** Identical single-item re-post with no id/time — could be a resend or a new message. */
    AMBIGUOUS_REPEAT,
}

/** Media result — the fourth user-facing dimension. Text success never implies media success. */
enum class MediaState {
    NONE,
    PENDING,
    LOCAL_COPY,
    PLACEHOLDER_ONLY,
    URI_EXPIRED,
    PERMISSION_DENIED,
    TOO_LARGE,
    DISABLED_BY_USER,
    FAILED,
}

data class Message(
    val id: Long,
    val conversationId: Long,
    val sourceMessageId: String?,
    val senderName: String?,
    val senderKey: String?,
    val isSelf: Boolean,
    val body: String,
    val kind: MessageKind,
    val sourceTimestampEpochMs: Long?,
    val timestampQuality: TimestampQuality,
    val observedAtEpochMs: Long,
    val postedAtEpochMs: Long?,
    val origin: CaptureOrigin,
    val contentStatus: ContentStatus,
    val dedupState: DedupState,
    val revisionCount: Int,
    val observationCount: Int,
    val mediaState: MediaState,
    val mediaBlobId: Long?,
    /** Deterministic ordering key: source time if present, else post time, else observed time. */
    val sortKey: Long,
)

data class MessageRevision(
    val id: Long,
    val messageId: Long,
    val body: String,
    val observedAtEpochMs: Long,
)
