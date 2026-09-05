package dev.quietinbox.core.analytics

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.GapInterval
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
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

    // ---------------------------------------------------------------------------------------
    // Period-scoped insights (plan section 13 extension). Every function below is pure and
    // counts the same rows as [compute]: `AMBIGUOUS_REPEAT` observations are excluded, because a
    // repeat may be the same message seen twice. Nothing here infers a reply, a read receipt or a
    // message the source app never put in a notification.
    // ---------------------------------------------------------------------------------------

    /** Rows an insight counts: not ambiguous, and inside [period] when one is given. */
    private fun counted(messages: List<ObservedMessage>, period: Period? = null): List<ObservedMessage> =
        messages.filter {
            it.dedupState != DedupState.AMBIGUOUS_REPEAT && (period == null || it.timestampEpochMs in period)
        }

    private fun ObservedMessage.localDateTime(zone: TimeZone) =
        Instant.fromEpochMilliseconds(timestampEpochMs).toLocalDateTime(zone)

    /**
     * Observed messages per weekday and local hour: `[weekday][hour]`, Monday first (index 0) and
     * hour 0..23. Rows are ISO weekdays in [zone], so a message at 23:59 Sunday and one at 00:00
     * Monday land in different rows.
     */
    fun heatmap(messages: List<ObservedMessage>, zone: TimeZone): Array<IntArray> {
        val grid = Array(DAYS_PER_WEEK) { IntArray(HOURS_PER_DAY) }
        for (m in counted(messages)) {
            val local = m.localDateTime(zone)
            grid[local.date.dayOfWeek.isoDayNumber - 1][local.hour]++
        }
        return grid
    }

    /**
     * Per-conversation observed counts for the period, split into weekdays (Mon-Fri) and weekend
     * days (Sat/Sun) in [zone]. `share` is the conversation's share of *that board's* total, so
     * each board sums to 1.0; boards are sorted by count descending, then conversation id.
     */
    fun rankings(messages: List<ObservedMessage>, period: Period, zone: TimeZone): RankingBoards {
        val rows = counted(messages, period)
        val weekdays = ArrayList<ObservedMessage>(rows.size)
        val weekends = ArrayList<ObservedMessage>()
        for (m in rows) {
            when (m.localDateTime(zone).date.dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> weekends += m
                else -> weekdays += m
            }
        }
        return RankingBoards(rank(rows), rank(weekdays), rank(weekends))
    }

    private fun rank(rows: List<ObservedMessage>): List<ConversationRank> {
        val total = rows.size.toDouble()
        return rows.groupingBy { it.conversationId }.eachCount().entries
            .map { ConversationRank(it.key, it.value, if (total == 0.0) 0.0 else it.value / total) }
            .sortedWith(compareByDescending<ConversationRank> { it.count }.thenBy { it.conversationId })
    }

    /**
     * The [TimeBand] each conversation was observed in most (ties resolve to the earlier band in
     * enum order), plus the distribution of every counted message across all five bands. The
     * distribution always carries all five entries in band order so a chart keeps stable slots.
     */
    fun bestTime(messages: List<ObservedMessage>, zone: TimeZone): BestTimeReport {
        val rows = counted(messages)
        val bands = TimeBand.entries
        val overall = IntArray(bands.size)
        val perConversation = LinkedHashMap<Long, IntArray>()
        for (m in rows) {
            val band = TimeBand.of(m.localDateTime(zone).hour)
            overall[band.ordinal]++
            perConversation.getOrPut(m.conversationId) { IntArray(bands.size) }[band.ordinal]++
        }
        val total = rows.size.toDouble()
        val distribution = bands.map {
            BandCount(it, overall[it.ordinal], if (total == 0.0) 0.0 else overall[it.ordinal] / total)
        }
        val perConversationRanks = perConversation.entries.map { (id, counts) ->
            var best = 0
            for (i in counts.indices) if (counts[i] > counts[best]) best = i
            val sum = counts.sum()
            ConversationBand(
                conversationId = id,
                band = bands[best],
                bandCount = counts[best],
                total = sum,
                share = if (sum == 0) 0.0 else counts[best].toDouble() / sum,
            )
        }.sortedWith(compareByDescending<ConversationBand> { it.total }.thenBy { it.conversationId })
        return BestTimeReport(perConversationRanks, distribution)
    }

    /**
     * Observed messages per *active day*, where an active day is a local day in [zone] on which at
     * least one message of that conversation was observed: `perActiveDay = count / activeDays`.
     * Days with nothing observed are not in the denominator, so this measures burst size rather
     * than how regularly a conversation ran; [quietRate] covers the other half.
     */
    fun chattiness(messages: List<ObservedMessage>, zone: TimeZone): List<ChattinessRank> {
        val counts = LinkedHashMap<Long, Int>()
        val activeDays = HashMap<Long, MutableSet<LocalDate>>()
        for (m in counted(messages)) {
            counts[m.conversationId] = (counts[m.conversationId] ?: 0) + 1
            activeDays.getOrPut(m.conversationId) { HashSet() } += m.localDateTime(zone).date
        }
        return counts.entries.map { (id, count) ->
            val days = activeDays[id]?.size ?: 0
            ChattinessRank(id, count, days, if (days == 0) 0.0 else count.toDouble() / days)
        }.sortedWith(
            compareByDescending<ChattinessRank> { it.perActiveDay }
                .thenByDescending { it.count }
                .thenBy { it.conversationId },
        )
    }

    /**
     * Share of the period's local days on which nothing was observed for a conversation:
     * `quietDays / period.days(zone).size`, plus the longest run of consecutive quiet days. The
     * denominator is every day of the period, including days before the conversation first
     * appeared, so a new conversation reads as quiet — the UI must say the period, not "ghosted".
     * Only conversations with at least one observed message in the period are returned.
     */
    fun quietRate(messages: List<ObservedMessage>, period: Period, zone: TimeZone): List<QuietRank> {
        val days = period.days(zone)
        val dayIndex = HashMap<LocalDate, Int>(days.size * 2)
        days.forEachIndexed { i, d -> dayIndex[d] = i }
        val seen = LinkedHashMap<Long, MutableSet<Int>>()
        for (m in counted(messages, period)) {
            val index = dayIndex[m.localDateTime(zone).date] ?: continue
            seen.getOrPut(m.conversationId) { HashSet() } += index
        }
        val total = days.size
        return seen.entries.map { (id, activeIndices) ->
            var longest = 0
            var run = 0
            for (i in 0 until total) {
                if (i in activeIndices) {
                    run = 0
                } else {
                    run++
                    if (run > longest) longest = run
                }
            }
            val quiet = total - activeIndices.size
            QuietRank(
                conversationId = id,
                quietDays = quiet,
                totalDays = total,
                rate = if (total == 0) 0.0 else quiet.toDouble() / total,
                longestQuietStreakDays = longest,
            )
        }.sortedWith(
            compareByDescending<QuietRank> { it.rate }
                .thenByDescending { it.longestQuietStreakDays }
                .thenBy { it.conversationId },
        )
    }

    /**
     * The phrases each sender repeated most often (see [PhraseScanner] for how a phrase is cut).
     * Messages flagged `isSelf` and messages without a sender name are skipped, matching
     * [ActivityReport.topSenders]: what the notification attributes to somebody else is all this
     * can honestly describe. A phrase needs [minCount] occurrences to appear at all.
     */
    fun catchphrases(
        messages: List<ObservedMessage>,
        minLen: Int = 2,
        top: Int = 20,
        minCount: Int = 2,
    ): List<SenderPhrases> {
        val bySender = LinkedHashMap<String, MutableList<ObservedMessage>>()
        for (m in counted(messages)) {
            if (m.isSelf) continue
            val name = m.senderName?.takeIf(String::isNotBlank) ?: continue
            bySender.getOrPut(name) { ArrayList() } += m
        }
        return bySender.entries.map { (sender, rows) ->
            val tally = HashMap<String, Int>()
            for (m in rows) for (p in PhraseScanner.phrases(m.body, minLen)) tally[p] = (tally[p] ?: 0) + 1
            val phrases = tally.entries.asSequence()
                .filter { it.value >= minCount }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(top)
                .map { PhraseCount(it.key, it.value) }
                .toList()
            SenderPhrases(sender, rows.size, phrases)
        }.filter { it.phrases.isNotEmpty() }
            .sortedWith(compareByDescending<SenderPhrases> { it.messageCount }.thenBy { it.sender })
    }

    /** Emoji ranking over an arbitrary slice, so a period selector can re-rank without [compute]. */
    fun emojiRanking(messages: List<ObservedMessage>, top: Int = 24): List<EmojiCount> {
        val tally = HashMap<String, Int>()
        for (m in counted(messages)) for (e in EmojiScanner.scan(m.body)) tally[e] = (tally[e] ?: 0) + 1
        return tally.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(top)
            .map { EmojiCount(it.key, it.value) }
    }

    private const val DAYS_PER_WEEK = 7
    private const val HOURS_PER_DAY = 24
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
