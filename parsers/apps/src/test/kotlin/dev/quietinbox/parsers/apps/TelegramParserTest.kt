package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationTemplate
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun tgSnapshot(shape: NotificationShape) = Fixtures.snapshot(shape, packageName = KnownSources.TELEGRAM)

class TelegramParserTest : FunSpec({
    val parser = TelegramParser()
    val standard = StandardParser()

    test("MessagingStyle is the normal path and preserves order and timestamps") {
        val shape = Fixtures.messaging(conversationTitle = "Dev chat", isGroup = true) {
            message("Ann", "build is green", 1_000)
            message("Ben", "shipping", 2_000)
            message("Ann", "thanks", 3_000)
        }
        val batch = parser.parse(tgSnapshot(shape))
        batch.messages.map { it.body } shouldBe listOf("build is green", "shipping", "thanks")
        batch.messages.all { it.timestampQuality == TimestampQuality.SOURCE_MESSAGE } shouldBe true
        batch.contentStatus shouldBe ContentStatus.FULL_STRUCTURED
        batch.parserId shouldBe "telegram"
    }

    test("several chats collapsed into one notification become a summary") {
        val shape = Fixtures.bigText("Telegram", "4 new messages from 3 chats")
        val batch = parser.parse(tgSnapshot(shape))
        batch.messages.shouldBeEmpty()
        batch.contentStatus shouldBe ContentStatus.SUMMARY_ONLY
        batch.summary.shouldNotBeNull().messageCount shouldBe 4
        batch.summary!!.conversationCount shouldBe 3
        batch.warnings shouldContain ParseWarning.MULTIPLE_CONVERSATIONS_SUSPECTED
        standard.parse(tgSnapshot(shape)).contentStatus shouldBe ContentStatus.UNKNOWN_FORMAT
    }

    test("sending progress yields zero messages") {
        val shape = Fixtures.base("Ann", "Sending...")
        val batch = parser.parse(tgSnapshot(shape))
        batch.messages.shouldBeEmpty()
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
        standard.parse(tgSnapshot(shape)).messages shouldHaveSize 1
    }

    test("upload progress yields zero messages") {
        parser.parse(tgSnapshot(Fixtures.base("Ann", "Uploading photo 30%"))).messages.shouldBeEmpty()
    }

    test("Telegram specific hidden preview wording is flagged") {
        val shape = Fixtures.bigText("Ann", "Message hidden")
        val batch = parser.parse(tgSnapshot(shape))
        batch.messages[0].contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.warnings shouldContain ParseWarning.PREVIEW_PLACEHOLDER
        standard.parse(tgSnapshot(shape)).contentStatus shouldBe ContentStatus.NOTIFICATION_TEXT
    }

    test("an ongoing notification that carries real messages keeps them and is only flagged") {
        val shape = Fixtures.messaging(conversationTitle = "Ann") {
            message("Ann", "still here", 1_000)
        }.copy(isOngoing = true)
        val batch = parser.parse(tgSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].body shouldBe "still here"
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
    }

    test("an unmodelled template falls back to the standard behaviour with a warning") {
        val shape = Fixtures.base("Ann", "hello").copy(template = NotificationTemplate.UNKNOWN)
        val batch = parser.parse(tgSnapshot(shape))
        batch.warnings shouldContain ParseWarning.ADAPTER_FALLBACK_TO_STANDARD
        batch.messages shouldHaveSize 1
    }
})
