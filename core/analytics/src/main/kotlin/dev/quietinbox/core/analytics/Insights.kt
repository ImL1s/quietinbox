package dev.quietinbox.core.analytics

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Which preset produced a [Period]; the UI uses it to label the selection. */
enum class PeriodKind { LAST_7_DAYS, THIS_MONTH, LAST_MONTH, LAST_3_MONTHS, ALL, CUSTOM }

/**
 * A half-open window `[startEpochMs, endEpochMsExclusive)` whose edges are local midnights in the
 * time zone the preset was built with. The end is always the start of *tomorrow*, so the current
 * day counts in full and no future day inflates a denominator.
 */
data class Period(
    val kind: PeriodKind,
    val startEpochMs: Long,
    val endEpochMsExclusive: Long,
) {
    operator fun contains(epochMs: Long): Boolean =
        epochMs >= startEpochMs && epochMs < endEpochMsExclusive

    /** Inclusive end instant, for DAO queries whose upper bound is inclusive. */
    val endEpochMsInclusive: Long get() = endEpochMsExclusive - 1

    /**
     * Every local date the window covers, first to last inclusive. This is the denominator used by
     * [ActivityAnalytics.quietRate]; an empty window yields an empty list.
     */
    fun days(zone: TimeZone): List<LocalDate> {
        if (endEpochMsExclusive <= startEpochMs) return emptyList()
        val first = Instant.fromEpochMilliseconds(startEpochMs).toLocalDateTime(zone).date
        val last = Instant.fromEpochMilliseconds(endEpochMsExclusive - 1).toLocalDateTime(zone).date
        val out = ArrayList<LocalDate>()
        var day = first
        while (day <= last) {
            out += day
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return out
    }

    companion object {
        /** `Period.all` never reaches further back than this, so a corrupt timestamp cannot explode the day list. */
        const val MAX_SPAN_DAYS: Int = 3660

        /** Today and the previous six local days. */
        fun last7Days(nowEpochMs: Long, zone: TimeZone): Period {
            val today = today(nowEpochMs, zone)
            return between(today.minus(DatePeriod(days = 6)), today, zone, PeriodKind.LAST_7_DAYS)
        }

        /** The first of the current local month up to and including today. */
        fun thisMonth(nowEpochMs: Long, zone: TimeZone): Period {
            val today = today(nowEpochMs, zone)
            return between(LocalDate(today.year, today.month, 1), today, zone, PeriodKind.THIS_MONTH)
        }

        /** The whole previous calendar month in the given zone. */
        fun lastMonth(nowEpochMs: Long, zone: TimeZone): Period {
            val firstOfThisMonth = today(nowEpochMs, zone).let { LocalDate(it.year, it.month, 1) }
            val firstOfLastMonth = firstOfThisMonth.minus(DatePeriod(months = 1))
            return between(firstOfLastMonth, firstOfThisMonth.minus(DatePeriod(days = 1)), zone, PeriodKind.LAST_MONTH)
        }

        /** A rolling three calendar months ending today inclusive. */
        fun last3Months(nowEpochMs: Long, zone: TimeZone): Period {
            val today = today(nowEpochMs, zone)
            val start = today.minus(DatePeriod(months = 3)).plus(DatePeriod(days = 1))
            return between(start, today, zone, PeriodKind.LAST_3_MONTHS)
        }

        /**
         * From the day of the earliest stored observation (or today when the vault is empty) up to
         * today, clamped to [MAX_SPAN_DAYS].
         */
        fun all(earliestEpochMs: Long?, nowEpochMs: Long, zone: TimeZone): Period {
            val today = today(nowEpochMs, zone)
            val floor = today.minus(DatePeriod(days = MAX_SPAN_DAYS))
            val earliest = earliestEpochMs
                ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }
                ?.coerceIn(floor, today)
                ?: today
            return between(earliest, today, zone, PeriodKind.ALL)
        }

        /** A user-picked inclusive date range; the dates are swapped when given the wrong way round. */
        fun custom(start: LocalDate, endInclusive: LocalDate, zone: TimeZone): Period {
            val (from, to) = if (endInclusive < start) endInclusive to start else start to endInclusive
            // Same clamp as [all]: a picker cannot turn one screen into an unbounded day loop.
            val floor = to.minus(DatePeriod(days = MAX_SPAN_DAYS))
            return between(maxOf(from, floor), to, zone, PeriodKind.CUSTOM)
        }

        private fun today(nowEpochMs: Long, zone: TimeZone): LocalDate =
            Instant.fromEpochMilliseconds(nowEpochMs).toLocalDateTime(zone).date

        private fun between(first: LocalDate, lastInclusive: LocalDate, zone: TimeZone, kind: PeriodKind): Period =
            Period(
                kind = kind,
                startEpochMs = first.atStartOfDayIn(zone).toEpochMilliseconds(),
                endEpochMsExclusive = lastInclusive.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(),
            )
    }
}

/**
 * The five local-hour bands the "best time" view reports. [NIGHT] wraps midnight
 * (`hour >= 22 || hour < 6`), the other four are half-open `[startHour, endHourExclusive)`.
 */
enum class TimeBand(val startHour: Int, val endHourExclusive: Int) {
    MORNING(6, 10),
    LEISURE(10, 14),
    AFTERNOON(14, 18),
    EVENING(18, 22),
    NIGHT(22, 6),
    ;

    companion object {
        fun of(hour: Int): TimeBand = when (hour) {
            in MORNING.startHour until MORNING.endHourExclusive -> MORNING
            in LEISURE.startHour until LEISURE.endHourExclusive -> LEISURE
            in AFTERNOON.startHour until AFTERNOON.endHourExclusive -> AFTERNOON
            in EVENING.startHour until EVENING.endHourExclusive -> EVENING
            else -> NIGHT
        }
    }
}

/** Per-conversation counts for the whole period, its weekdays only and its weekend days only. */
data class RankingBoards(
    val allDays: List<ConversationRank>,
    val weekdays: List<ConversationRank>,
    val weekends: List<ConversationRank>,
)

/** The band a conversation was observed in most, with its share of that conversation's messages. */
data class ConversationBand(
    val conversationId: Long,
    val band: TimeBand,
    val bandCount: Int,
    val total: Int,
    val share: Double,
)

data class BandCount(val band: TimeBand, val count: Int, val share: Double)

data class BestTimeReport(
    val perConversation: List<ConversationBand>,
    val distribution: List<BandCount>,
)

/** Observed messages per active day, where an active day is a local day with at least one message. */
data class ChattinessRank(
    val conversationId: Long,
    val count: Int,
    val activeDays: Int,
    val perActiveDay: Double,
)

/** Share of the period's days on which nothing was observed for a conversation. */
data class QuietRank(
    val conversationId: Long,
    val quietDays: Int,
    val totalDays: Int,
    val rate: Double,
    val longestQuietStreakDays: Int,
)

data class PhraseCount(val phrase: String, val count: Int)

/** The phrases one sender repeated most often, with how many of their messages were scanned. */
data class SenderPhrases(val sender: String, val messageCount: Int, val phrases: List<PhraseCount>, val packageName: String = "", val conversationId: Long = 0L)

/**
 * Repeated-phrase extraction over observed message bodies. CJK runs yield character n-grams,
 * Latin runs yield lower-cased words and word bigrams. URLs, digits, punctuation and emoji never
 * become phrases: they end the run they appear in.
 *
 * N-grams overlap, so a single "哈哈哈" already contains "哈哈" twice; the counts describe character
 * sequences observed, not distinct utterances.
 */
object PhraseScanner {
    /** Longest CJK n-gram emitted; two- and three-character phrases are what a catchphrase looks like. */
    const val MAX_CJK_GRAM: Int = 3

    private val URL = Regex("(?i)(?:https?://|www\\.)\\S+")

    private val CJK_STOP = setOf(
        '的', '了', '是', '我', '你', '妳', '他', '她', '它', '在', '有', '就', '不', '也', '都',
        '很', '這', '那', '個', '們', '和', '與', '之', '啊', '吧', '呢', '嗎', '喔', '哦', '嘛',
        '啦', '欸', '呀', '唉', '一', '要', '會', '對', '好',
    )

    private val LATIN_STOP = setOf(
        "the", "and", "you", "are", "for", "that", "this", "with", "have", "was", "but", "not",
        "can", "out", "get", "got", "its", "they", "them", "from", "your", "all", "our", "has",
        "had", "will", "just", "about", "what", "when", "then", "there", "here", "his", "her",
        "she", "him", "were", "been", "who", "why", "how", "one", "too", "yes", "yeah", "ok",
        "okay", "any", "not", "did", "does", "let", "now", "some", "than", "into", "over",
    )

    /**
     * All candidate phrases in [text], with duplicates kept so callers can count them.
     * [minLen] is the shortest CJK n-gram (and, as a word length floor, the shortest Latin word is
     * always at least three letters, per the product spec).
     */
    fun phrases(text: String, minLen: Int = 2): List<String> {
        val out = ArrayList<String>()
        val cleaned = URL.replace(text, " ")
        val gramFloor = minLen.coerceAtLeast(2)
        val cjk = StringBuilder()
        val word = StringBuilder()
        var chain = ArrayList<String>()

        fun flushCjk() {
            if (cjk.isNotEmpty()) {
                emitGrams(cjk.toString(), gramFloor, out)
                cjk.setLength(0)
            }
        }

        fun flushChain() {
            emitLatin(chain, out)
            chain = ArrayList()
        }

        fun flushWord(breaksChain: Boolean) {
            if (word.isNotEmpty()) {
                val w = word.toString().lowercase()
                word.setLength(0)
                if (w.length >= 3) chain += w else flushChain()
            }
            if (breaksChain) flushChain()
        }

        var i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            val len = Character.charCount(cp)
            when {
                isCjk(cp) -> {
                    flushWord(breaksChain = true)
                    cjk.appendCodePoint(cp)
                }
                isLatinLetter(cp) -> {
                    flushCjk()
                    word.appendCodePoint(cp)
                }
                cp == ' '.code || cp == '\''.code || cp == '-'.code || cp == 0x2019 -> {
                    flushCjk()
                    flushWord(breaksChain = false)
                }
                else -> {
                    flushCjk()
                    flushWord(breaksChain = true)
                }
            }
            i += len
        }
        flushCjk()
        flushWord(breaksChain = true)
        return out
    }

    private fun emitGrams(run: String, minLen: Int, out: MutableList<String>) {
        val chars = run.toCharArray()
        for (size in minLen..MAX_CJK_GRAM) {
            if (chars.size < size) break
            for (start in 0..chars.size - size) {
                var allStop = true
                for (k in start until start + size) {
                    if (chars[k] !in CJK_STOP) {
                        allStop = false
                        break
                    }
                }
                if (!allStop) out += String(chars, start, size)
            }
        }
    }

    private fun emitLatin(chain: List<String>, out: MutableList<String>) {
        for (w in chain) if (w !in LATIN_STOP) out += w
        for (k in 0 until chain.size - 1) {
            val a = chain[k]
            val b = chain[k + 1]
            if (a !in LATIN_STOP && b !in LATIN_STOP) out += "$a $b"
        }
    }

    private fun isLatinLetter(cp: Int): Boolean =
        cp < 0x0250 && Character.isLetter(cp)

    private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
        -> true
        else -> false
    }
}
