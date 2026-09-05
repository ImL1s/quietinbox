package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.ContentStatus
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

private fun waSnapshot(shape: NotificationShape) = Fixtures.snapshot(shape, packageName = KnownSources.WHATSAPP)

class WhatsAppParserTest : FunSpec({
    val parser = WhatsAppParser()
    val standard = StandardParser()

    test("MessagingStyle keeps message order and never claims a source message id") {
        val shape = Fixtures.messaging(conversationTitle = "Alice", shortcutId = "sc-wa-1") {
            message("Alice", "hi", 1_000)
            message("Alice", "are you there?", 2_000)
            message(null, "on my way", 3_000, isSelf = true)
        }
        val batch = parser.parse(waSnapshot(shape))
        batch.messages.map { it.body } shouldBe listOf("hi", "are you there?", "on my way")
        batch.messages[2].sender?.isSelf shouldBe true
        batch.messages.all { it.sourceMessageId == null } shouldBe true
        batch.contentStatus shouldBe ContentStatus.FULL_STRUCTURED
        batch.parserId shouldBe "whatsapp"
    }

    test("checking for new messages is a status notice, not a hidden preview") {
        val shape = Fixtures.base("WhatsApp", "Checking for new messages")
        val batch = parser.parse(waSnapshot(shape))
        batch.messages.shouldBeEmpty()
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
        batch.warnings shouldNotContain ParseWarning.PREVIEW_PLACEHOLDER
        standard.parse(waSnapshot(shape)).messages shouldHaveSize 1
    }

    test("backup progress yields zero messages") {
        val batch = parser.parse(waSnapshot(Fixtures.base("WhatsApp", "Backing up messages 45%")))
        batch.messages.shouldBeEmpty()
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
    }

    test("a collapsed multi chat summary is reported with both counts") {
        val shape = Fixtures.bigText("WhatsApp", "5 new messages from 2 chats")
        val batch = parser.parse(waSnapshot(shape))
        batch.messages.shouldBeEmpty()
        batch.contentStatus shouldBe ContentStatus.SUMMARY_ONLY
        batch.summary.shouldNotBeNull().messageCount shouldBe 5
        batch.summary!!.conversationCount shouldBe 2
        batch.warnings shouldContain ParseWarning.MULTIPLE_CONVERSATIONS_SUSPECTED
    }

    test("a multi line group body is split into one candidate per line") {
        val shape = Fixtures.bigText("Family", "Alice: dinner?", bigText = "Alice: dinner?\nBob: 7pm")
        val batch = parser.parse(waSnapshot(shape))
        batch.messages shouldHaveSize 2
        batch.messages.map { it.sender?.displayName } shouldBe listOf("Alice", "Bob")
        batch.messages.map { it.body } shouldBe listOf("dinner?", "7pm")
        batch.messages.map { it.ordinal } shouldBe listOf(0, 1)
        batch.warnings shouldContain ParseWarning.SENDER_SPLIT_HEURISTIC
        standard.parse(waSnapshot(shape)).messages shouldHaveSize 1
    }

    test("a multi line body from a single sender is left intact") {
        val shape = Fixtures.bigText("Alice", "Alice: one", bigText = "Alice: one\nAlice: two")
        val batch = parser.parse(waSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].body shouldBe "Alice: one\nAlice: two"
        batch.warnings shouldNotContain ParseWarning.SENDER_SPLIT_HEURISTIC
    }

    test("WhatsApp specific hidden preview wording is flagged") {
        val shape = Fixtures.bigText("Alice", "您可能有新訊息")
        val batch = parser.parse(waSnapshot(shape))
        batch.messages[0].contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.warnings shouldContain ParseWarning.PREVIEW_PLACEHOLDER
        standard.parse(waSnapshot(shape)).contentStatus shouldBe ContentStatus.NOTIFICATION_TEXT
    }

    test("an unmodelled template falls back to the standard behaviour with a warning") {
        val shape = Fixtures.base("Alice", "hi").copy(template = NotificationTemplate.UNKNOWN)
        parser.parse(waSnapshot(shape)).warnings shouldContain ParseWarning.ADAPTER_FALLBACK_TO_STANDARD
    }
})
