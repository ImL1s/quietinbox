package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MessageKind
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

private fun igSnapshot(shape: NotificationShape) = Fixtures.snapshot(shape, packageName = KnownSources.INSTAGRAM)

class InstagramParserTest : FunSpec({
    val parser = InstagramParser()
    val standard = StandardParser()

    test("MessagingStyle keeps message order and senders") {
        val shape = Fixtures.messaging(conversationTitle = "Alice", shortcutId = "sc-ig-1") {
            message("Alice", "hey", 1_000)
            message("Alice", "you around?", 2_000)
        }
        val batch = parser.parse(igSnapshot(shape))
        batch.messages.map { it.body } shouldBe listOf("hey", "you around?")
        batch.messages.all { it.sourceMessageId == null } shouldBe true
        batch.contentStatus shouldBe ContentStatus.FULL_STRUCTURED
        batch.parserId shouldBe "instagram"
    }

    test("a named sent you a message body is treated as a hidden preview") {
        val shape = Fixtures.bigText("Alice", "Alice sent you a message")
        val batch = parser.parse(igSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.warnings shouldContain ParseWarning.PREVIEW_PLACEHOLDER
        standard.parse(igSnapshot(shape)).contentStatus shouldBe ContentStatus.NOTIFICATION_TEXT
    }

    test("a group thread body is split when the prefix differs from the thread title") {
        val shape = Fixtures.bigText("Close Friends", "Alice: hey")
        val batch = parser.parse(igSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].sender?.displayName shouldBe "Alice"
        batch.messages[0].body shouldBe "hey"
        batch.warnings shouldContain ParseWarning.SENDER_SPLIT_HEURISTIC
        standard.parse(igSnapshot(shape)).messages[0].body shouldBe "Alice: hey"
    }

    test("a one to one body that repeats the title is left intact") {
        val batch = parser.parse(igSnapshot(Fixtures.bigText("Alice", "Alice: hey")))
        batch.messages[0].body shouldBe "Alice: hey"
        batch.warnings shouldNotContain ParseWarning.SENDER_SPLIT_HEURISTIC
    }

    test("a reaction is kept verbatim but marked as a system message") {
        val shape = Fixtures.bigText("Alice", "Alice liked your message")
        val batch = parser.parse(igSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].kind shouldBe MessageKind.SYSTEM
        batch.messages[0].body shouldBe "Alice liked your message"
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
        standard.parse(igSnapshot(shape)).messages[0].kind shouldBe MessageKind.TEXT
    }

    test("an ongoing notification without items yields zero messages") {
        val batch = parser.parse(igSnapshot(Fixtures.base("Instagram", "Uploading", isOngoing = true)))
        batch.messages.shouldBeEmpty()
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
    }

    test("a bare summary line becomes a summary observation") {
        val batch = parser.parse(igSnapshot(Fixtures.bigText("Instagram", "2 new messages")))
        batch.messages.shouldBeEmpty()
        batch.contentStatus shouldBe ContentStatus.SUMMARY_ONLY
        batch.summary.shouldNotBeNull().messageCount shouldBe 2
    }

    test("an unmodelled template falls back to the standard behaviour with a warning") {
        val shape = Fixtures.base("Alice", "hey").copy(template = NotificationTemplate.UNKNOWN)
        parser.parse(igSnapshot(shape)).warnings shouldContain ParseWarning.ADAPTER_FALLBACK_TO_STANDARD
    }
})
