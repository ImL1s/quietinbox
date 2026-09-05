package dev.quietinbox.core.analytics

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.GapInterval
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A single observed message reduced to what analytics needs. */
data class ObservedMessage(
    val conversationId: Long,
    val packageName: String,
    val timestampEpochMs: Long,
    val dedupState: DedupState,
    val contentStatus: ContentStatus,
    val body: String,
    val senderName: String?,
    val isSelf: Boolean,
)

data class AnalyticsInput(
    val messages: List<ObservedMessage>,
    val summaryOnlyCount: Int,
    val gaps: List<GapInterval>,
    val rangeStartEpochMs: Long,
    val rangeEndEpochMs: Long,
    val timeZone: TimeZone,
)

data class DayCount(val date: LocalDate, val count: Int)
data class ConversationRank(val conversationId: Long, val count: Int, val share: Double)
data class SenderRank(val name: String, val count: Int)
data class EmojiCount(val emoji: String, val count: Int)
data class SourceCount(val packageName: String, val count: Int)

/**
 * Descriptive statistics over *observed* data only (plan section 13). Nothing here claims a
 * "real message total", a reply rate or a recall rate; every number carries its sample size,
 * range, time zone and the count of ambiguous / summary-only observations it excludes.
 */
data class ActivityReport(
    val sampleSize: Int,
    val confirmedCount: Int,
    val ambiguousCount: Int,
    val summaryOnlyCount: Int,
    val previewRestrictedCount: Int,
    val rangeStartEpochMs: Long,
    val rangeEndEpochMs: Long,
    val timeZoneId: String,
    val hourly: List<Int>,
    val daily: List<DayCount>,
    val topConversations: List<ConversationRank>,
    val topSenders: List<SenderRank>,
    val emoji: List<EmojiCount>,
    val sources: List<SourceCount>,
    val gapCount: Int,
    val unknownGapCount: Int,
    val medianIntervalMs: Long?,
    val conversationCount: Int,
) {
    val isEmpty: Boolean get() = sampleSize == 0
}

object ActivityAnalytics {
    private const val TOP_N = 8

    fun compute(input: AnalyticsInput): ActivityReport {
        val counted = input.messages.filter { it.dedupState != DedupState.AMBIGUOUS_REPEAT }
        val ambiguous = input.messages.size - counted.size
        val tz = input.timeZone

        val hourly = IntArray(24)
        val daily = LinkedHashMap<LocalDate, Int>()
        for (m in counted) {
            val ldt = Instant.fromEpochMilliseconds(m.timestampEpochMs).toLocalDateTime(tz)
            hourly[ldt.hour]++
            daily[ldt.date] = (daily[ldt.date] ?: 0) + 1
        }

        val perConversation = counted.groupingBy { it.conversationId }.eachCount()
        val total = counted.size.toDouble().coerceAtLeast(1.0)
        val topConversations = perConversation.entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .map { ConversationRank(it.key, it.value, it.value / total) }

        val topSenders = counted.asSequence()
            .filter { !it.isSelf }
            .mapNotNull { it.senderName?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .map { SenderRank(it.key, it.value) }

        val emoji = HashMap<String, Int>()
        for (m in counted) for (e in EmojiScanner.scan(m.body)) emoji[e] = (emoji[e] ?: 0) + 1
        val topEmoji = emoji.entries.sortedByDescending { it.value }.take(12).map { EmojiCount(it.key, it.value) }

        val sources = counted.groupingBy { it.packageName }.eachCount().entries
            .sortedByDescending { it.value }
            .map { SourceCount(it.key, it.value) }

        val intervals = counted.map { it.timestampEpochMs }.sorted().zipWithNext { a, b -> b - a }.filter { it >= 0 }
        val median = if (intervals.isEmpty()) null else intervals.sorted()[intervals.size / 2]

        return ActivityReport(
            sampleSize = input.messages.size,
            confirmedCount = counted.count { it.dedupState == DedupState.CONFIRMED },
            ambiguousCount = ambiguous,
            summaryOnlyCount = input.summaryOnlyCount,
            previewRestrictedCount = counted.count { it.contentStatus == ContentStatus.PREVIEW_RESTRICTED_SUSPECTED },
            rangeStartEpochMs = input.rangeStartEpochMs,
            rangeEndEpochMs = input.rangeEndEpochMs,
            timeZoneId = tz.id,
            hourly = hourly.toList(),
            daily = daily.entries.sortedBy { it.key }.map { DayCount(it.key, it.value) },
            topConversations = topConversations,
            topSenders = topSenders,
            emoji = topEmoji,
            sources = sources,
            gapCount = input.gaps.size,
            unknownGapCount = input.gaps.count { it.startEpochMs == null || it.endEpochMs == null },
            medianIntervalMs = median,
            conversationCount = perConversation.size,
        )
    }
}

/** Minimal emoji cluster scanner: pictographs, ZWJ sequences, skin tones and flags. */
object EmojiScanner {
    fun scan(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (isEmojiBase(cp)) {
                val sb = StringBuilder().appendCodePoint(cp)
                var j = i + len
                var expectJoined = false
                while (j < n) {
                    val c = text.codePointAt(j)
                    val cl = Character.charCount(c)
                    when {
                        c == ZWJ -> { sb.appendCodePoint(c); expectJoined = true }
                        c == VS16 || c in SKIN_TONES || c in TAG_RANGE -> sb.appendCodePoint(c)
                        expectJoined && isEmojiBase(c) -> { sb.appendCodePoint(c); expectJoined = false }
                        isRegionalIndicator(cp) && isRegionalIndicator(c) && sb.codePointCount(0, sb.length) == 1 -> sb.appendCodePoint(c)
                        else -> break
                    }
                    j += cl
                }
                out += sb.toString()
                i = j
            } else {
                i += len
            }
        }
        return out
    }

    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    private val TAG_RANGE = 0xE0020..0xE007F

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x1F000..0x1F2FF ||
            cp in 0x2B00..0x2BFF ||
            cp == 0x2764 || cp == 0x2763 || cp == 0x2B50 || cp == 0x2B55 ||
            cp in 0x1F1E6..0x1F1FF
}
