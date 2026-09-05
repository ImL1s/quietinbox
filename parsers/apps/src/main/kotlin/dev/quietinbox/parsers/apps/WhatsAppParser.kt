package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.core.parser.TextHeuristics

/**
 * WhatsApp (`com.whatsapp`).
 *
 * Adds two things on top of the standard behaviour:
 *  - status wording (`Checking for new messages`, `WhatsApp Web is currently active`, backup
 *    progress) is classified as a notice and yields zero messages;
 *  - a multi-line plain body whose every line carries a `Sender: ` prefix is split into one
 *    candidate per line, which the standard parser keeps as a single blob.
 *
 * Every phrase is a synthetic guess (see README, SYNTHETIC_ONLY).
 */
class WhatsAppParser : AppParser() {
    override val id: String = "whatsapp"
    override val packages: Set<String> = setOf(KnownSources.WHATSAPP)

    override val placeholderPhrases: Set<String> = setOf(
        "您可能有新訊息",
        "你可能有新訊息",
        "you have unread messages",
    )

    override val noticePhrases: Set<String> = setOf(
        "checking for new messages",
        "正在檢查新訊息",
        "whatsapp web is currently active",
        "whatsapp web 目前已啟用",
    )

    override val noticePrefixes: Set<String> = setOf(
        "backing up",
        "backup",
        "restoring",
        "正在備份",
        "正在還原",
    )

    override fun appSingleCandidates(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> {
        val shape = snapshot.shape
        val body = pickBody(shape) ?: return super.appSingleCandidates(snapshot, warnings)
        val lines = body.split('\n').map(String::trim).filter(String::isNotEmpty)
        if (lines.size < 2) return super.appSingleCandidates(snapshot, warnings)

        val split = lines.map { TextHeuristics.splitSenderPrefix(it) }
        if (split.any { it == null }) return super.appSingleCandidates(snapshot, warnings)
        val pairs = split.filterNotNull()

        val senders = pairs.map { it.first }
        val title = shape.title?.value
        val looksLikeGroup = shape.isGroupConversation == true ||
            (senders.distinct().size >= 2 && senders.none { it == title })
        if (!looksLikeGroup) return super.appSingleCandidates(snapshot, warnings)

        warnings += ParseWarning.SENDER_SPLIT_HEURISTIC
        val (timestamp, quality) = timestamp(null, snapshot)
        if (quality == TimestampQuality.OBSERVED_ONLY) warnings += ParseWarning.NO_TIMESTAMP
        return pairs.mapIndexed { index, pair ->
            MessageCandidate(
                ordinal = index,
                body = pair.second,
                sender = SenderCandidate(displayName = pair.first),
                sourceTimestampEpochMs = timestamp,
                timestampQuality = quality,
                contentStatus = ContentStatus.NOTIFICATION_TEXT,
            )
        }
    }
}
