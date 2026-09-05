package dev.quietinbox.core.parser

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.MessageKind
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.core.model.TruncationFlag
import dev.quietinbox.core.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class StandardParserTest : FunSpec({
    val parser = StandardParser()

    test("MessagingStyle with three messages yields three candidates in order") {
        val shape = Fixtures.messaging(conversationTitle = "Team", isGroup = true, shortcutId = "sc-1") {
            message("Alice", "A", 1_000)
            message("Bob", "B", 2_000)
            message("Alice", "C", 3_000)
        }
        val batch = parser.parse(Fixtures.snapshot(shape))
        batch.messages.map { it.body } shouldBe listOf("A", "B", "C")
        batch.messages.map { it.sender?.displayName } shouldBe listOf("Alice", "Bob", "Alice")
        batch.messages.all { it.timestampQuality == TimestampQuality.SOURCE_MESSAGE } shouldBe true
        batch.contentStatus shouldBe ContentStatus.FULL_STRUCTURED
        batch.conversation.shouldNotBeNull().isGroup shouldBe true
        batch.conversation!!.displayTitle shouldBe "Team"
    }

    test("group summary yields a summary observation and zero messages") {
        val batch = parser.parse(Fixtures.snapshot(Fixtures.summary("Messenger", "5 new messages from 2 chats")))
        batch.messages.shouldBeEmpty()
        batch.summary.shouldNotBeNull().messageCount shouldBe 5
        batch.summary!!.conversationCount shouldBe 2
        batch.contentStatus shouldBe ContentStatus.SUMMARY_ONLY
    }

    test("Chinese summary line is recognised") {
        TextHeuristics.parseSummary("3 則新訊息") shouldBe Pair(3, null)
        TextHeuristics.parseSummary("來自 2 個聊天室的 7 則訊息") shouldBe Pair(7, 2)
    }

    test("inbox lines split sender prefix conservatively") {
        val shape = Fixtures.inbox("Family", listOf("Mom: dinner?", "Dad: 7pm", "http://x.y/z"))
        val batch = parser.parse(Fixtures.snapshot(shape))
        batch.messages shouldHaveSize 3
        batch.messages[0].sender?.displayName shouldBe "Mom"
        batch.messages[0].body shouldBe "dinner?"
        batch.messages[2].body shouldBe "http://x.y/z"
        batch.messages[2].sender?.displayName shouldBe "Family"
        batch.warnings shouldContain ParseWarning.SENDER_SPLIT_HEURISTIC
    }

    test("preview placeholder is flagged, not stored as real content") {
        val batch = parser.parse(Fixtures.snapshot(Fixtures.bigText("小明", "您有一則新訊息")))
        batch.messages shouldHaveSize 1
        batch.messages[0].contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.warnings shouldContain ParseWarning.PREVIEW_PLACEHOLDER
    }

    test("empty notification yields EMPTY status and no messages") {
        val batch = parser.parse(Fixtures.snapshot(Fixtures.base(null, null)))
        batch.messages.shouldBeEmpty()
        batch.contentStatus shouldBe ContentStatus.EMPTY
    }

    test("system notice is flagged") {
        val batch = parser.parse(Fixtures.snapshot(Fixtures.base("Alice", "Missed call")))
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
    }

    test("media message without text is kept as MEDIA kind") {
        val shape = Fixtures.messaging(conversationTitle = "Alice") {
            message("Alice", null, 1_000, mimeType = "image/jpeg", dataUri = "content://x/1")
        }
        val batch = parser.parse(Fixtures.snapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].kind shouldBe MessageKind.MEDIA
        batch.messages[0].media?.uri shouldBe "content://x/1"
    }

    test("historic messages are included and flagged") {
        val shape = Fixtures.messaging(conversationTitle = "Alice") {
            historic("Alice", "old", 500)
            message("Alice", "new", 1_000)
        }
        val batch = parser.parse(Fixtures.snapshot(shape))
        batch.messages.map { it.body } shouldBe listOf("old", "new")
        batch.messages[0].isHistoric shouldBe true
        batch.warnings shouldContain ParseWarning.HISTORIC_INCLUDED
    }

    test("truncated input carries a warning") {
        val shape = Fixtures.bigText("A", "b").copy(truncated = setOf(TruncationFlag.TEXT))
        parser.parse(Fixtures.snapshot(shape)).warnings shouldContain ParseWarning.TRUNCATED_INPUT
    }
})
