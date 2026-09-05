package dev.quietinbox.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MessageKind { TEXT, MEDIA, STICKER, CALL, SYSTEM, UNKNOWN }

/** How much the pipeline trusts a timestamp. Never used to rewrite the source value. */
@Serializable
enum class TimestampQuality {
    /** The source attached a per-message timestamp (e.g. `MessagingStyle.Message.timestamp`). */
    SOURCE_MESSAGE,

    /** Only `Notification.when` was available. */
    NOTIFICATION_WHEN,

    /** Only `StatusBarNotification.postTime` was available. */
    NOTIFICATION_POST_TIME,

    /** Only QuietInbox's own observation time exists. */
    OBSERVED_ONLY,
}

/** Content quality of what a notification exposed — one of the four user-facing dimensions. */
@Serializable
enum class ContentStatus {
    /** Structured items (MessagingStyle / inbox lines) were fully present. */
    FULL_STRUCTURED,

    /** Only the plain notification title/text existed. */
    NOTIFICATION_TEXT,

    /** The notification was a summary ("3 new messages") without bodies. */
    SUMMARY_ONLY,

    /** The body looks like a placeholder — the source likely hides previews. */
    PREVIEW_RESTRICTED_SUSPECTED,

    /** The template is not understood; raw fields are kept but nothing is claimed. */
    UNKNOWN_FORMAT,

    /** Nothing textual was present. */
    EMPTY,
}

@Serializable
enum class Confidence { VERIFIED, INFERRED, LOW }

@Serializable
enum class IdentityEvidenceKind {
    SOURCE_CHAT_ID, SHORTCUT_ID, GROUP_KEY, CONVERSATION_TITLE, NOTIFICATION_TAG, CHANNEL_ID, SENDER_NAME, SENDER_KEY, SENDER_URI, TITLE,
}

/** A single piece of identity evidence with its trust level. Never a merged conclusion. */
@Serializable
data class IdentityEvidence(
    val kind: IdentityEvidenceKind,
    val value: String,
    val confidence: Confidence,
)

@Serializable
enum class ParseWarning {
    TRUNCATED_INPUT,
    NO_SENDER,
    NO_TIMESTAMP,
    HISTORIC_INCLUDED,
    SUMMARY_WITHOUT_ITEMS,
    UNEXPECTED_TEMPLATE,
    POSSIBLE_SYSTEM_NOTICE,
    PREVIEW_PLACEHOLDER,
    SENDER_SPLIT_HEURISTIC,
    MULTIPLE_CONVERSATIONS_SUSPECTED,
    EMPTY_BODY_DROPPED,
    ADAPTER_FALLBACK_TO_STANDARD,
}

/** Strongly-typed conversation key candidates, from most to least trustworthy. */
@Serializable
sealed interface ConversationKey {
    val value: String

    @Serializable
    data class SourceChatId(override val value: String) : ConversationKey

    @Serializable
    data class ShortcutId(override val value: String) : ConversationKey

    /** Notification stream boundary (tag/id or group) — a hint, *not* a chat id. */
    @Serializable
    data class NotificationStream(override val value: String) : ConversationKey
}

@Serializable
data class ConversationCandidate(
    val key: ConversationKey? = null,
    val displayTitle: String? = null,
    val isGroup: Boolean? = null,
    val participants: List<String> = emptyList(),
)

@Serializable
data class SenderCandidate(
    val displayName: String? = null,
    val senderKey: String? = null,
    val isSelf: Boolean = false,
)

@Serializable
data class MediaReferenceCandidate(
    val mimeType: String? = null,
    /** `content://` only. */
    val uri: String? = null,
    /** True when the notification carried a bitmap/icon rather than a URI. */
    val fromNotificationBitmap: Boolean = false,
)

/** One message a parser believes it observed. Zero or more of these per snapshot. */
@Serializable
data class MessageCandidate(
    val ordinal: Int,
    val body: String,
    val sender: SenderCandidate? = null,
    /** Only set when the adapter has fixture-proven that this id is stable. */
    val sourceMessageId: String? = null,
    val sourceTimestampEpochMs: Long? = null,
    val timestampQuality: TimestampQuality = TimestampQuality.OBSERVED_ONLY,
    val kind: MessageKind = MessageKind.TEXT,
    val media: MediaReferenceCandidate? = null,
    val contentStatus: ContentStatus = ContentStatus.NOTIFICATION_TEXT,
    val isHistoric: Boolean = false,
)

@Serializable
data class SummaryObservation(
    val text: String,
    val messageCount: Int? = null,
    val conversationCount: Int? = null,
)

/**
 * Output of a parser. A single snapshot may legitimately yield zero messages (summary only),
 * one, or many. Parsers never touch a database, a clock or the network.
 */
@Serializable
data class ParsedBatch(
    val conversation: ConversationCandidate?,
    val messages: List<MessageCandidate>,
    val summary: SummaryObservation?,
    val contentStatus: ContentStatus,
    val identityEvidence: List<IdentityEvidence>,
    val warnings: Set<ParseWarning>,
    val parserId: String,
    val parserVersion: String,
) {
    companion object {
        fun empty(parserId: String, parserVersion: String, status: ContentStatus = ContentStatus.EMPTY) = ParsedBatch(
            conversation = null,
            messages = emptyList(),
            summary = null,
            contentStatus = status,
            identityEvidence = emptyList(),
            warnings = emptySet(),
            parserId = parserId,
            parserVersion = parserVersion,
        )
    }
}
