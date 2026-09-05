package dev.quietinbox.core.parser

import dev.quietinbox.core.model.BoundedText
import dev.quietinbox.core.model.Confidence
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.ConversationCandidate
import dev.quietinbox.core.model.ConversationKey
import dev.quietinbox.core.model.IdentityEvidence
import dev.quietinbox.core.model.IdentityEvidenceKind
import dev.quietinbox.core.model.MediaReferenceCandidate
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.MessageKind
import dev.quietinbox.core.model.MessagingMessageShape
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.NotificationTemplate
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.model.ParsedBatch
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.SummaryObservation
import dev.quietinbox.core.model.TimestampQuality

/**
 * Generic parser built purely on public Android notification semantics:
 * `MessagingStyle` → structured messages, `InboxStyle` lines → one candidate per line,
 * `BigTextStyle` / base → a single candidate, group summaries → [SummaryObservation].
 *
 * It never claims a source message id (none is public) and never merges conversations.
 */
open class StandardParser : NotificationParser {
    override val id: String = "standard"
    override val version: String = "1.0.0"
    override val packages: Set<String> = emptySet()

    override fun parse(snapshot: NotificationSnapshot): ParsedBatch {
        val shape = snapshot.shape
        val warnings = LinkedHashSet<ParseWarning>()
        if (shape.truncated.isNotEmpty()) warnings += ParseWarning.TRUNCATED_INPUT

        if (shape.isGroupSummary) return summaryBatch(snapshot, warnings)

        val evidence = identityEvidence(shape)
        val conversation = conversationCandidate(shape)

        val messages: List<MessageCandidate> = when {
            shape.messages.isNotEmpty() || shape.historicMessages.isNotEmpty() -> messagingCandidates(snapshot, warnings)
            shape.textLines.isNotEmpty() -> inboxCandidates(snapshot, warnings)
            else -> singleCandidate(snapshot, warnings)
        }

        val status = when {
            messages.isEmpty() && shape.title == null && shape.text == null && shape.bigText == null -> ContentStatus.EMPTY
            messages.isEmpty() -> ContentStatus.UNKNOWN_FORMAT
            messages.any { it.contentStatus == ContentStatus.PREVIEW_RESTRICTED_SUSPECTED } -> ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
            shape.messages.isNotEmpty() || shape.textLines.isNotEmpty() -> ContentStatus.FULL_STRUCTURED
            else -> ContentStatus.NOTIFICATION_TEXT
        }
        if (shape.template == NotificationTemplate.UNKNOWN && messages.isNotEmpty()) warnings += ParseWarning.UNEXPECTED_TEMPLATE

        return ParsedBatch(
            conversation = conversation,
            messages = messages,
            summary = null,
            contentStatus = status,
            identityEvidence = evidence,
            warnings = warnings,
            parserId = id,
            parserVersion = version,
        )
    }

    protected open fun summaryBatch(snapshot: NotificationSnapshot, warnings: MutableSet<ParseWarning>): ParsedBatch {
        val shape = snapshot.shape
        val text = shape.summaryText?.value ?: shape.text?.value ?: shape.title?.value
        val counts = TextHeuristics.parseSummary(text) ?: TextHeuristics.parseSummary(shape.title?.value)
        if (text == null) warnings += ParseWarning.SUMMARY_WITHOUT_ITEMS
        return ParsedBatch(
            conversation = null,
            messages = emptyList(),
            summary = SummaryObservation(text = text ?: "", messageCount = counts?.first, conversationCount = counts?.second),
            contentStatus = ContentStatus.SUMMARY_ONLY,
            identityEvidence = shape.groupKey?.let { listOf(IdentityEvidence(IdentityEvidenceKind.GROUP_KEY, it, Confidence.LOW)) }.orEmpty(),
            warnings = warnings,
            parserId = id,
            parserVersion = version,
        )
    }

    protected open fun identityEvidence(shape: NotificationShape): List<IdentityEvidence> {
        val out = ArrayList<IdentityEvidence>()
        shape.shortcutId?.let { out += IdentityEvidence(IdentityEvidenceKind.SHORTCUT_ID, it, Confidence.INFERRED) }
        shape.groupKey?.let { out += IdentityEvidence(IdentityEvidenceKind.GROUP_KEY, it, Confidence.LOW) }
        shape.tag?.let { out += IdentityEvidence(IdentityEvidenceKind.NOTIFICATION_TAG, it, Confidence.LOW) }
        shape.channelId?.let { out += IdentityEvidence(IdentityEvidenceKind.CHANNEL_ID, it, Confidence.LOW) }
        shape.conversationTitle?.let { out += IdentityEvidence(IdentityEvidenceKind.CONVERSATION_TITLE, it.value, Confidence.LOW) }
        shape.title?.let { out += IdentityEvidence(IdentityEvidenceKind.TITLE, it.value, Confidence.LOW) }
        return out
    }

    protected open fun conversationCandidate(shape: NotificationShape): ConversationCandidate {
        val key: ConversationKey? = shape.shortcutId?.let { ConversationKey.ShortcutId(it) }
        val title = shape.conversationTitle?.value ?: shape.titleBig?.value ?: shape.title?.value
        val participants = shape.messages.mapNotNull { it.senderName?.value }.distinct()
        val isGroup = shape.isGroupConversation ?: when {
            participants.size > 1 -> true
            else -> null
        }
        return ConversationCandidate(key = key, displayTitle = title, isGroup = isGroup, participants = participants)
    }

    protected open fun messagingCandidates(snapshot: NotificationSnapshot, warnings: MutableSet<ParseWarning>): List<MessageCandidate> {
        val shape = snapshot.shape
        val out = ArrayList<MessageCandidate>()
        if (shape.historicMessages.isNotEmpty()) warnings += ParseWarning.HISTORIC_INCLUDED
        var ordinal = 0
        for (m in shape.historicMessages) messagingCandidate(m, ordinal++, snapshot, warnings, historic = true)?.let(out::add)
        for (m in shape.messages) messagingCandidate(m, ordinal++, snapshot, warnings, historic = false)?.let(out::add)
        return out
    }

    private fun messagingCandidate(
        m: MessagingMessageShape,
        ordinal: Int,
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
        historic: Boolean,
    ): MessageCandidate? {
        val text = m.text?.value
        val hasMedia = m.dataUri != null || m.dataMimeType != null
        if (text.isNullOrBlank() && !hasMedia) {
            warnings += ParseWarning.EMPTY_BODY_DROPPED
            return null
        }
        if (m.senderName == null && !m.isSelf) warnings += ParseWarning.NO_SENDER
        val (ts, quality) = timestamp(m.timestampEpochMs, snapshot)
        if (quality == TimestampQuality.OBSERVED_ONLY) warnings += ParseWarning.NO_TIMESTAMP
        val placeholder = TextHeuristics.isPreviewPlaceholder(text)
        if (placeholder) warnings += ParseWarning.PREVIEW_PLACEHOLDER
        val kind = when {
            hasMedia && m.dataMimeType?.startsWith("image/") == true -> MessageKind.MEDIA
            hasMedia -> MessageKind.MEDIA
            else -> MessageKind.TEXT
        }
        return MessageCandidate(
            ordinal = ordinal,
            body = text ?: "",
            sender = SenderCandidate(displayName = m.senderName?.value, senderKey = m.senderKey ?: m.senderUri, isSelf = m.isSelf),
            sourceMessageId = null,
            sourceTimestampEpochMs = ts,
            timestampQuality = quality,
            kind = kind,
            media = if (hasMedia) MediaReferenceCandidate(mimeType = m.dataMimeType, uri = m.dataUri) else null,
            contentStatus = if (placeholder) ContentStatus.PREVIEW_RESTRICTED_SUSPECTED else ContentStatus.FULL_STRUCTURED,
            isHistoric = historic,
        )
    }

    protected open fun inboxCandidates(snapshot: NotificationSnapshot, warnings: MutableSet<ParseWarning>): List<MessageCandidate> {
        val shape = snapshot.shape
        val (ts, quality) = timestamp(null, snapshot)
        val isGroup = shape.isGroupConversation == true
        return shape.textLines.mapIndexedNotNull { i, line ->
            val raw = line.value
            if (raw.isBlank()) {
                warnings += ParseWarning.EMPTY_BODY_DROPPED
                return@mapIndexedNotNull null
            }
            val split = TextHeuristics.splitSenderPrefix(raw)
            val titleValue = shape.title?.value
            val sender = when {
                split != null -> {
                    warnings += ParseWarning.SENDER_SPLIT_HEURISTIC
                    SenderCandidate(displayName = split.first)
                }
                !isGroup && titleValue != null -> SenderCandidate(displayName = titleValue)
                else -> null
            }
            val body = split?.second ?: raw
            val placeholder = TextHeuristics.isPreviewPlaceholder(body)
            if (placeholder) warnings += ParseWarning.PREVIEW_PLACEHOLDER
            if (sender == null) warnings += ParseWarning.NO_SENDER
            MessageCandidate(
                ordinal = i,
                body = body,
                sender = sender,
                sourceTimestampEpochMs = ts,
                timestampQuality = quality,
                contentStatus = if (placeholder) ContentStatus.PREVIEW_RESTRICTED_SUSPECTED else ContentStatus.FULL_STRUCTURED,
            )
        }
    }

    protected open fun singleCandidate(snapshot: NotificationSnapshot, warnings: MutableSet<ParseWarning>): List<MessageCandidate> {
        val shape = snapshot.shape
        val body = pickBody(shape) ?: return emptyList()
        if (TextHeuristics.looksLikeSystemNotice(body) || TextHeuristics.looksLikeSystemNotice(shape.title?.value) || shape.isOngoing) {
            warnings += ParseWarning.POSSIBLE_SYSTEM_NOTICE
        }
        val summary = TextHeuristics.parseSummary(body)
        if (summary != null) {
            warnings += ParseWarning.SUMMARY_WITHOUT_ITEMS
            return emptyList()
        }
        val (ts, quality) = timestamp(null, snapshot)
        val placeholder = TextHeuristics.isPreviewPlaceholder(body)
        if (placeholder) warnings += ParseWarning.PREVIEW_PLACEHOLDER
        val split = if (shape.isGroupConversation == true) TextHeuristics.splitSenderPrefix(body) else null
        if (split != null) warnings += ParseWarning.SENDER_SPLIT_HEURISTIC
        val sender = split?.let { SenderCandidate(displayName = it.first) }
            ?: shape.title?.let { SenderCandidate(displayName = it.value) }
        if (sender == null) warnings += ParseWarning.NO_SENDER
        val hasPicture = shape.hasPicture || shape.pictureUri != null
        return listOf(
            MessageCandidate(
                ordinal = 0,
                body = split?.second ?: body,
                sender = sender,
                sourceTimestampEpochMs = ts,
                timestampQuality = quality,
                kind = if (hasPicture) MessageKind.MEDIA else MessageKind.TEXT,
                media = if (hasPicture) MediaReferenceCandidate(uri = shape.pictureUri, fromNotificationBitmap = shape.pictureUri == null) else null,
                contentStatus = if (placeholder) ContentStatus.PREVIEW_RESTRICTED_SUSPECTED else ContentStatus.NOTIFICATION_TEXT,
            ),
        )
    }

    protected fun pickBody(shape: NotificationShape): String? {
        val big = shape.bigText?.value
        val text = shape.text?.value
        return when {
            !big.isNullOrBlank() -> big
            !text.isNullOrBlank() -> text
            else -> null
        }
    }

    protected fun timestamp(messageTs: Long?, snapshot: NotificationSnapshot): Pair<Long?, TimestampQuality> {
        val whenMs = snapshot.shape.whenEpochMs
        return when {
            messageTs != null && messageTs > 0 -> messageTs to TimestampQuality.SOURCE_MESSAGE
            whenMs != null && whenMs > 0 -> whenMs to TimestampQuality.NOTIFICATION_WHEN
            snapshot.postedAtEpochMs != null -> snapshot.postedAtEpochMs to TimestampQuality.NOTIFICATION_POST_TIME
            else -> null to TimestampQuality.OBSERVED_ONLY
        }
    }

    protected fun BoundedText?.orEmpty(): String = this?.value.orEmpty()
}
