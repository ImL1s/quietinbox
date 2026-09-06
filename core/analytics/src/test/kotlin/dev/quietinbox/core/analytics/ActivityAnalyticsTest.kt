package dev.quietinbox.core.analytics

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone

private fun obs(ts: Long, conv: Long = 1, body: String = "hi", dedup: DedupState = DedupState.CONFIRMED, sender: String? = "A", self: Boolean = false, pkg: String = "pkg") =
    ObservedMessage(conv, pkg, ts, dedup, ContentStatus.FULL_STRUCTURED, body, sender, self)

class ActivityAnalyticsTest : FunSpec({
    val taipei = TimeZone.of("Asia/Taipei")

    test("ambiguous observations are reported but excluded from counts") {
        val report = ActivityAnalytics.compute(
            AnalyticsInput(
                messages = listOf(obs(1_700_000_000_000L), obs(1_700_000_000_000L, dedup = DedupState.AMBIGUOUS_REPEAT)),
                summaryOnlyCount = 3,
                gaps = emptyList(),
                rangeStartEpochMs = 0,
                rangeEndEpochMs = 2_000_000_000_000L,
                timeZone = taipei,
            ),
        )
        report.sampleSize shouldBe 2
        report.confirmedCount shouldBe 1
        report.ambiguousCount shouldBe 1
        report.summaryOnlyCount shouldBe 3
        report.hourly.sum() shouldBe 1
        report.timeZoneId shouldBe "Asia/Taipei"
    }

    test("hourly buckets honour the requested time zone") {
        // 2023-11-14T22:13:20Z == 2023-11-15T06:13:20 Asia/Taipei
        val report = ActivityAnalytics.compute(AnalyticsInput(listOf(obs(1_700_000_000_000L)), 0, emptyList(), 0, 2_000_000_000_000L, taipei))
        report.hourly[6] shouldBe 1
        val utc = ActivityAnalytics.compute(AnalyticsInput(listOf(obs(1_700_000_000_000L)), 0, emptyList(), 0, 2_000_000_000_000L, TimeZone.UTC))
        utc.hourly[22] shouldBe 1
    }

    test("emoji clusters are counted including ZWJ sequences and skin tones") {
        EmojiScanner.scan("好 👍🏽 ok 👨‍👩‍👧 😀😀") shouldBe listOf("👍🏽", "👨‍👩‍👧", "😀", "😀")
        val report = ActivityAnalytics.compute(AnalyticsInput(listOf(obs(1, body = "😀😀 🇹🇼")), 0, emptyList(), 0, 10, taipei))
        report.emoji.map { it.emoji to it.count } shouldContain ("😀" to 2)
        report.emoji.map { it.emoji } shouldContain "🇹🇼"
    }

    test("top conversations carry share of the counted sample") {
        val report = ActivityAnalytics.compute(
            AnalyticsInput(listOf(obs(1, conv = 1), obs(2, conv = 1), obs(3, conv = 2), obs(4, conv = 2, dedup = DedupState.AMBIGUOUS_REPEAT)), 0, emptyList(), 0, 10, taipei),
        )
        report.topConversations.first().conversationId shouldBe 1L
        report.topConversations.first().share shouldBe (2.0 / 3.0)
        report.conversationCount shouldBe 2
    }

    // ---- QI-SEARCH-011 ------------------------------------------------------------------------

    test("the median interval is taken within conversations, never across them") {
        val h = 3_600_000L
        // Conversation 1 posts hourly; conversation 2 posts one minute after each of them.
        val messages = (0 until 6).flatMap { i -> listOf(obs(1_700_000_000_000L + i * h, conv = 1), obs(1_700_000_000_000L + i * h + 60_000L, conv = 2)) }
        val report = ActivityAnalytics.compute(AnalyticsInput(messages, 0, emptyList(), 0L, Long.MAX_VALUE, taipei))
        // Across conversations the gaps would be 1 min / 59 min; within each they are exactly one hour.
        report.medianIntervalMs shouldBe h
        report.intervalSampleSize shouldBe 10
    }

    test("same-named senders in different apps or chats are ranked separately") {
        val messages = listOf(
            obs(1L, conv = 1, sender = "Alice", pkg = "a"), obs(2L, conv = 1, sender = "Alice", pkg = "a"), obs(3L, conv = 1, sender = "Alice", pkg = "a"),
            obs(4L, conv = 2, sender = "Alice", pkg = "b"), obs(5L, conv = 2, sender = "Alice", pkg = "b"),
            obs(6L, conv = 3, sender = "Bob", pkg = "a"),
        )
        val report = ActivityAnalytics.compute(AnalyticsInput(messages, 0, emptyList(), 0L, Long.MAX_VALUE, taipei))
        report.topSenders.map { Triple(it.name, it.packageName, it.count) } shouldBe listOf(
            Triple("Alice", "a", 3), Triple("Alice", "b", 2), Triple("Bob", "a", 1),
        )
    }
})
