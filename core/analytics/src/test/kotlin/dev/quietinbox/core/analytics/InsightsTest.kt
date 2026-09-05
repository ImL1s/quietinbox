package dev.quietinbox.core.analytics

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** Asia/Taipei is UTC+8 all year, so every expectation below is DST-free. */
private val taipei = TimeZone.of("Asia/Taipei")

private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: TimeZone = taipei): Long =
    LocalDateTime(year, month, day, hour, minute).toInstant(zone).toEpochMilliseconds()

private fun msg(
    ts: Long,
    conv: Long = 1,
    body: String = "hi",
    sender: String? = "A",
    self: Boolean = false,
    dedup: DedupState = DedupState.CONFIRMED,
) = ObservedMessage(conv, "pkg", ts, dedup, ContentStatus.FULL_STRUCTURED, body, sender, self)

/** Sunday 2026-09-06 12:00 local; the reference "now" for every period preset test. */
private val now = at(2026, 9, 6, 12)

class InsightsTest : FunSpec({

    // ----------------------------------------------------------------------------- periods

    test("last7Days covers today and the six previous local days") {
        val period = Period.last7Days(now, taipei)
        val days = period.days(taipei)
        days.size shouldBe 7
        days.first() shouldBe LocalDate(2026, 8, 31)
        days.last() shouldBe LocalDate(2026, 9, 6)
        period.kind shouldBe PeriodKind.LAST_7_DAYS
    }

    test("thisMonth starts at the first of the month and ends after today") {
        val period = Period.thisMonth(now, taipei)
        period.days(taipei).size shouldBe 6
        period.startEpochMs shouldBe at(2026, 9, 1, 0)
        period.endEpochMsExclusive shouldBe at(2026, 9, 7, 0)
        period.endEpochMsInclusive shouldBe at(2026, 9, 7, 0) - 1
    }

    test("lastMonth is the whole previous calendar month") {
        val period = Period.lastMonth(now, taipei)
        val days = period.days(taipei)
        days.size shouldBe 31
        days.first() shouldBe LocalDate(2026, 8, 1)
        days.last() shouldBe LocalDate(2026, 8, 31)
    }

    test("last3Months is a rolling three calendar months ending today") {
        val period = Period.last3Months(now, taipei)
        val days = period.days(taipei)
        days.first() shouldBe LocalDate(2026, 6, 7)
        days.last() shouldBe LocalDate(2026, 9, 6)
        days.size shouldBe 92
    }

    test("all spans from the earliest observation, and is a single day when the vault is empty") {
        Period.all(null, now, taipei).days(taipei) shouldContainExactly listOf(LocalDate(2026, 9, 6))
        val period = Period.all(at(2026, 9, 3, 23), now, taipei)
        period.days(taipei).first() shouldBe LocalDate(2026, 9, 3)
        period.days(taipei).size shouldBe 4
    }

    test("all clamps an impossible earliest timestamp to the maximum span") {
        val period = Period.all(0L, now, taipei)
        period.days(taipei).size shouldBe Period.MAX_SPAN_DAYS + 1
    }

    test("custom accepts a reversed range and is half-open on the end") {
        val period = Period.custom(LocalDate(2026, 9, 6), LocalDate(2026, 9, 1), taipei)
        period.kind shouldBe PeriodKind.CUSTOM
        period.days(taipei).size shouldBe 6
        (at(2026, 9, 1, 0) in period) shouldBe true
        (at(2026, 9, 6, 23) in period) shouldBe true
        (at(2026, 9, 7, 0) in period) shouldBe false
        (at(2026, 8, 31, 23) in period) shouldBe false
    }

    // ----------------------------------------------------------------------------- heat map

    test("heatmap is empty for no messages and Monday-first for one") {
        val empty = ActivityAnalytics.heatmap(emptyList(), taipei)
        empty.size shouldBe 7
        empty.all { row -> row.size == 24 && row.all { it == 0 } } shouldBe true

        val monday = ActivityAnalytics.heatmap(listOf(msg(at(2026, 9, 7, 0))), taipei)
        monday[0][0] shouldBe 1
        monday.sumOf { it.sum() } shouldBe 1
    }

    test("heatmap puts Sunday 23:59 and Monday 00:00 in different rows") {
        val messages = listOf(msg(at(2026, 9, 6, 23, 59)), msg(at(2026, 9, 7, 0, 0)))
        val grid = ActivityAnalytics.heatmap(messages, taipei)
        grid[6][23] shouldBe 1
        grid[0][0] shouldBe 1
    }

    test("heatmap follows the requested zone") {
        val messages = listOf(msg(at(2026, 9, 7, 0, 0)))
        ActivityAnalytics.heatmap(messages, TimeZone.UTC)[6][16] shouldBe 1
    }

    test("heatmap excludes ambiguous repeats") {
        val messages = listOf(msg(at(2026, 9, 7, 9)), msg(at(2026, 9, 7, 9), dedup = DedupState.AMBIGUOUS_REPEAT))
        ActivityAnalytics.heatmap(messages, taipei).sumOf { it.sum() } shouldBe 1
    }

    // ----------------------------------------------------------------------------- rankings

    test("rankings split weekdays from weekend days and carry each board's share") {
        val period = Period.custom(LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), taipei)
        val messages = listOf(
            msg(at(2026, 9, 5, 10), conv = 1),
            msg(at(2026, 9, 5, 11), conv = 1),
            msg(at(2026, 9, 7, 10), conv = 1),
            msg(at(2026, 9, 7, 11), conv = 2),
            msg(at(2026, 8, 31, 10), conv = 2),
        )
        val boards = ActivityAnalytics.rankings(messages, period, taipei)
        boards.allDays.map { it.conversationId to it.count } shouldContainExactly listOf(1L to 3, 2L to 1)
        boards.allDays.first().share shouldBe (0.75 plusOrMinus 1e-9)
        boards.weekends.map { it.conversationId to it.count } shouldContainExactly listOf(1L to 2)
        boards.weekends.first().share shouldBe (1.0 plusOrMinus 1e-9)
        boards.weekdays.map { it.conversationId to it.count } shouldContainExactly listOf(1L to 1, 2L to 1)
        boards.weekdays.first().share shouldBe (0.5 plusOrMinus 1e-9)
    }

    test("rankings of nothing are three empty boards") {
        val boards = ActivityAnalytics.rankings(emptyList(), Period.last7Days(now, taipei), taipei)
        boards.allDays.shouldBeEmpty()
        boards.weekdays.shouldBeEmpty()
        boards.weekends.shouldBeEmpty()
    }

    // ---------------------------------------------------------------------------- best time

    test("time bands cover every hour and wrap midnight") {
        listOf(6, 9).map { TimeBand.of(it) }.toSet() shouldBe setOf(TimeBand.MORNING)
        listOf(10, 13).map { TimeBand.of(it) }.toSet() shouldBe setOf(TimeBand.LEISURE)
        listOf(14, 17).map { TimeBand.of(it) }.toSet() shouldBe setOf(TimeBand.AFTERNOON)
        listOf(18, 21).map { TimeBand.of(it) }.toSet() shouldBe setOf(TimeBand.EVENING)
        listOf(22, 23, 0, 5).map { TimeBand.of(it) }.toSet() shouldBe setOf(TimeBand.NIGHT)
    }

    test("bestTime reports a dominant band per conversation and a five-slot distribution") {
        val messages = listOf(
            msg(at(2026, 9, 7, 7), conv = 1),
            msg(at(2026, 9, 8, 7), conv = 1),
            msg(at(2026, 9, 7, 23), conv = 1),
            msg(at(2026, 9, 7, 20), conv = 2),
        )
        val report = ActivityAnalytics.bestTime(messages, taipei)
        report.distribution.map { it.band } shouldContainExactly TimeBand.entries.toList()
        report.distribution.single { it.band == TimeBand.MORNING }.count shouldBe 2
        report.distribution.sumOf { it.share } shouldBe (1.0 plusOrMinus 1e-9)
        val first = report.perConversation.first()
        first.conversationId shouldBe 1L
        first.band shouldBe TimeBand.MORNING
        first.bandCount shouldBe 2
        first.total shouldBe 3
        first.share shouldBe (2.0 / 3.0 plusOrMinus 1e-9)
        report.perConversation[1].band shouldBe TimeBand.EVENING
    }

    test("a tie in bestTime resolves to the earlier band") {
        val messages = listOf(msg(at(2026, 9, 7, 7)), msg(at(2026, 9, 7, 23)))
        ActivityAnalytics.bestTime(messages, taipei).perConversation.single().band shouldBe TimeBand.MORNING
    }

    test("bestTime of nothing still lists all five bands at zero") {
        val report = ActivityAnalytics.bestTime(emptyList(), taipei)
        report.perConversation.shouldBeEmpty()
        report.distribution.size shouldBe 5
        report.distribution.all { it.count == 0 && it.share == 0.0 } shouldBe true
    }

    // --------------------------------------------------------------------------- chattiness

    test("chattiness divides observed messages by active days") {
        val messages = listOf(
            msg(at(2026, 9, 1, 9), conv = 1),
            msg(at(2026, 9, 1, 10), conv = 1),
            msg(at(2026, 9, 1, 11), conv = 1),
            msg(at(2026, 9, 2, 9), conv = 1),
            msg(at(2026, 9, 2, 9), conv = 2),
        )
        val ranks = ActivityAnalytics.chattiness(messages, taipei)
        ranks.map { it.conversationId } shouldContainExactly listOf(1L, 2L)
        ranks[0].count shouldBe 4
        ranks[0].activeDays shouldBe 2
        ranks[0].perActiveDay shouldBe (2.0 plusOrMinus 1e-9)
        ranks[1].perActiveDay shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("chattiness of a single message is one message on one active day") {
        val ranks = ActivityAnalytics.chattiness(listOf(msg(at(2026, 9, 1, 9))), taipei)
        ranks.single().activeDays shouldBe 1
        ranks.single().perActiveDay shouldBe (1.0 plusOrMinus 1e-9)
        ActivityAnalytics.chattiness(emptyList(), taipei).shouldBeEmpty()
    }

    // ---------------------------------------------------------------------------- quiet rate

    test("quietRate counts the period's silent days and the longest silent streak") {
        val period = Period.custom(LocalDate(2026, 9, 1), LocalDate(2026, 9, 7), taipei)
        val messages = listOf(
            msg(at(2026, 9, 1, 9), conv = 1),
            msg(at(2026, 9, 7, 9), conv = 1),
            msg(at(2026, 9, 4, 9), conv = 2),
        )
        val ranks = ActivityAnalytics.quietRate(messages, period, taipei)
        ranks.map { it.conversationId } shouldContainExactly listOf(2L, 1L)
        val conv1 = ranks.single { it.conversationId == 1L }
        conv1.totalDays shouldBe 7
        conv1.quietDays shouldBe 5
        conv1.rate shouldBe (5.0 / 7.0 plusOrMinus 1e-9)
        conv1.longestQuietStreakDays shouldBe 5
        val conv2 = ranks.single { it.conversationId == 2L }
        conv2.quietDays shouldBe 6
        conv2.longestQuietStreakDays shouldBe 3
    }

    test("quietRate ignores messages outside the period and reports nothing without data") {
        val period = Period.custom(LocalDate(2026, 9, 1), LocalDate(2026, 9, 7), taipei)
        ActivityAnalytics.quietRate(listOf(msg(at(2026, 8, 20, 9))), period, taipei).shouldBeEmpty()
        ActivityAnalytics.quietRate(emptyList(), period, taipei).shouldBeEmpty()
    }

    // -------------------------------------------------------------------------- catchphrases

    test("CJK catchphrases come from character bigrams and trigrams") {
        val messages = listOf(msg(at(2026, 9, 1, 9), body = "哈哈哈"), msg(at(2026, 9, 2, 9), body = "哈哈哈"))
        val phrases = ActivityAnalytics.catchphrases(messages).single()
        phrases.sender shouldBe "A"
        phrases.messageCount shouldBe 2
        phrases.phrases.first() shouldBe PhraseCount("哈哈", 4)
        phrases.phrases.single { it.phrase == "哈哈哈" }.count shouldBe 2
    }

    test("an n-gram made only of stop characters is dropped") {
        val messages = listOf(msg(at(2026, 9, 1, 9), body = "我的我的"), msg(at(2026, 9, 2, 9), body = "我的我的"))
        ActivityAnalytics.catchphrases(messages).shouldBeEmpty()
    }

    test("Latin catchphrases are lower-cased words and word bigrams without stop words") {
        val messages = listOf(
            msg(at(2026, 9, 1, 9), body = "See you Tomorrow morning"),
            msg(at(2026, 9, 2, 9), body = "see you tomorrow morning"),
        )
        val phrases = ActivityAnalytics.catchphrases(messages).single().phrases.associate { it.phrase to it.count }
        phrases["tomorrow morning"] shouldBe 2
        phrases["see"] shouldBe 2
        phrases.keys.contains("you") shouldBe false
        phrases.keys.any { it.contains("you") } shouldBe false
    }

    test("URLs, digits and punctuation never become phrases") {
        val messages = listOf(
            msg(at(2026, 9, 1, 9), body = "check https://example.com/abc 0912345678 later!"),
            msg(at(2026, 9, 2, 9), body = "check https://example.com/abc 0912345678 later!"),
        )
        val phrases = ActivityAnalytics.catchphrases(messages).single().phrases.map { it.phrase }
        phrases shouldContainExactly listOf("check", "later")
    }

    test("a phrase seen once is not a catchphrase, and own messages are never scanned") {
        // "早安" is one bigram and too short for a trigram, so a single message yields one occurrence.
        ActivityAnalytics.catchphrases(listOf(msg(at(2026, 9, 1, 9), body = "早安"))).shouldBeEmpty()
        val own = listOf(
            msg(at(2026, 9, 1, 9), body = "哈哈哈", self = true, sender = "me"),
            msg(at(2026, 9, 2, 9), body = "哈哈哈", self = true, sender = "me"),
        )
        ActivityAnalytics.catchphrases(own).shouldBeEmpty()
        ActivityAnalytics.catchphrases(emptyList()).shouldBeEmpty()
    }

    test("catchphrases are grouped per sender") {
        val messages = listOf(
            msg(at(2026, 9, 1, 9), body = "哈哈哈", sender = "A"),
            msg(at(2026, 9, 2, 9), body = "哈哈哈", sender = "A"),
            msg(at(2026, 9, 1, 9), body = "掰掰掰", sender = "B"),
            msg(at(2026, 9, 2, 9), body = "掰掰掰", sender = "B"),
            msg(at(2026, 9, 3, 9), body = "掰掰掰", sender = "B"),
        )
        val senders = ActivityAnalytics.catchphrases(messages)
        senders.map { it.sender } shouldContainExactly listOf("B", "A")
        senders.first().phrases.first().phrase shouldBe "掰掰"
    }

    // ---------------------------------------------------------------------------- emoji rank

    test("emojiRanking re-ranks an arbitrary slice") {
        val messages = listOf(
            msg(at(2026, 9, 1, 9), body = "😀😀"),
            msg(at(2026, 9, 2, 9), body = "😀 👍"),
            msg(at(2026, 9, 3, 9), body = "😀", dedup = DedupState.AMBIGUOUS_REPEAT),
        )
        ActivityAnalytics.emojiRanking(messages).map { it.emoji to it.count } shouldContainExactly
            listOf("😀" to 3, "👍" to 1)
        ActivityAnalytics.emojiRanking(emptyList()).shouldBeEmpty()
    }
})
