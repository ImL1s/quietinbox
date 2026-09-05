package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.ConversationKey
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationTemplate
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun lineSnapshot(shape: NotificationShape) = Fixtures.snapshot(shape, packageName = KnownSources.LINE)

class LineParserTest : FunSpec({
    val parser = LineParser()
    val standard = StandardParser()

    test("MessagingStyle keeps message order, senders and the shortcut key") {
        val shape = Fixtures.messaging(conversationTitle = "家族群組", isGroup = true, shortcutId = "sc-line-1") {
            message("小明", "晚餐吃什麼？", 1_000)
            message("小華", "火鍋", 2_000)
            message("小明", "好", 3_000)
        }
        val batch = parser.parse(lineSnapshot(shape))
        batch.messages.map { it.body } shouldBe listOf("晚餐吃什麼？", "火鍋", "好")
        batch.messages.map { it.sender?.displayName } shouldBe listOf("小明", "小華", "小明")
        batch.messages.all { it.sourceMessageId == null } shouldBe true
        batch.contentStatus shouldBe ContentStatus.FULL_STRUCTURED
        batch.conversation.shouldNotBeNull().key shouldBe ConversationKey.ShortcutId("sc-line-1")
        batch.parserId shouldBe "line"
        batch.parserVersion shouldBe "0.1.0"
    }

    test("LINE specific hidden preview wording is flagged where the standard parser sees plain text") {
        val shape = Fixtures.bigText("小明", "您有新的訊息")
        val batch = parser.parse(lineSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.warnings shouldContain ParseWarning.PREVIEW_PLACEHOLDER
        standard.parse(lineSnapshot(shape)).contentStatus shouldBe ContentStatus.NOTIFICATION_TEXT
    }

    test("a bare summary line becomes a summary observation instead of a message") {
        val shape = Fixtures.bigText("LINE", "3 則新訊息")
        val batch = parser.parse(lineSnapshot(shape))
        batch.messages.shouldBeEmpty()
        batch.contentStatus shouldBe ContentStatus.SUMMARY_ONLY
        batch.summary.shouldNotBeNull().messageCount shouldBe 3
        batch.summary!!.conversationCount shouldBe null
        batch.warnings shouldNotContain ParseWarning.SUMMARY_WITHOUT_ITEMS
        batch.warnings shouldNotContain ParseWarning.MULTIPLE_CONVERSATIONS_SUSPECTED
        standard.parse(lineSnapshot(shape)).summary shouldBe null
    }

    test("call wording produces zero messages and a system notice warning") {
        val shape = Fixtures.base("小明", "語音通話中")
        val batch = parser.parse(lineSnapshot(shape))
        batch.messages.shouldBeEmpty()
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
        batch.contentStatus shouldBe ContentStatus.NOTIFICATION_TEXT
        standard.parse(lineSnapshot(shape)).messages shouldHaveSize 1
    }

    test("ongoing notifications produce zero messages") {
        val batch = parser.parse(lineSnapshot(Fixtures.base("小明", "傳送中", isOngoing = true)))
        batch.messages.shouldBeEmpty()
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
    }

    test("spaced colon group form is split even though isGroupConversation is unset") {
        val shape = Fixtures.bigText("家族群組", "小明 : 晚餐吃什麼？")
        val batch = parser.parse(lineSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].sender?.displayName shouldBe "小明"
        batch.messages[0].body shouldBe "晚餐吃什麼？"
        batch.warnings shouldContain ParseWarning.SENDER_SPLIT_HEURISTIC
        standard.parse(lineSnapshot(shape)).messages[0].body shouldBe "小明 : 晚餐吃什麼？"
    }

    test("a declared one-to-one chat is never split and a time is never mistaken for a sender") {
        val declared = Fixtures.bigText("小明", "小華 : 你好").copy(isGroupConversation = false)
        parser.parse(lineSnapshot(declared)).messages[0].body shouldBe "小華 : 你好"

        val time = Fixtures.bigText("小明", "12:30 見")
        parser.parse(lineSnapshot(time)).messages[0].body shouldBe "12:30 見"
    }

    test("an unmodelled template falls back to the standard behaviour with a warning") {
        val shape = Fixtures.base("小明", "晚安").copy(template = NotificationTemplate.UNKNOWN)
        val batch = parser.parse(lineSnapshot(shape))
        batch.warnings shouldContain ParseWarning.ADAPTER_FALLBACK_TO_STANDARD
        batch.messages shouldHaveSize 1
        batch.messages[0].body shouldBe "晚安"
    }
})
