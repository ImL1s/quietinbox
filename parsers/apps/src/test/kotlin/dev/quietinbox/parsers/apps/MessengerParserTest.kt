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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun fbSnapshot(shape: NotificationShape) = Fixtures.snapshot(shape, packageName = KnownSources.MESSENGER)

class MessengerParserTest : FunSpec({
    val parser = MessengerParser()
    val standard = StandardParser()

    test("MessagingStyle keeps message order and senders") {
        val shape = Fixtures.messaging(conversationTitle = "Book club", isGroup = true) {
            message("Cara", "chapter 3 tonight", 1_000)
            message("Dan", "on it", 2_000)
            message("Cara", "great", 3_000)
        }
        val batch = parser.parse(fbSnapshot(shape))
        batch.messages.map { it.body } shouldBe listOf("chapter 3 tonight", "on it", "great")
        batch.messages.map { it.sender?.displayName } shouldBe listOf("Cara", "Dan", "Cara")
        batch.contentStatus shouldBe ContentStatus.FULL_STRUCTURED
        batch.parserId shouldBe "messenger"
    }

    test("attachment wording becomes a media candidate that records the bitmap only") {
        val shape = Fixtures.messaging(conversationTitle = "Cara") {
            message("Cara", "sent a photo", 1_000)
        }.copy(hasPicture = true)
        val batch = parser.parse(fbSnapshot(shape))
        batch.messages shouldHaveSize 1
        batch.messages[0].kind shouldBe MessageKind.MEDIA
        batch.messages[0].body shouldBe "sent a photo"
        batch.messages[0].media.shouldNotBeNull().fromNotificationBitmap shouldBe true
        batch.messages[0].media!!.uri shouldBe null
        standard.parse(fbSnapshot(shape)).messages[0].kind shouldBe MessageKind.TEXT
    }

    test("Chinese attachment wording without a bitmap is media with no reference") {
        val batch = parser.parse(fbSnapshot(Fixtures.bigText("Cara", "傳送了一張相片")))
        batch.messages[0].kind shouldBe MessageKind.MEDIA
        batch.messages[0].media.shouldNotBeNull().fromNotificationBitmap shouldBe false
        batch.messages[0].body shouldBe "傳送了一張相片"
    }

    test("an existing media reference is never overwritten") {
        val shape = Fixtures.messaging(conversationTitle = "Cara") {
            message("Cara", "sent a photo", 1_000, mimeType = "image/jpeg", dataUri = "content://fb/1")
        }
        val batch = parser.parse(fbSnapshot(shape))
        batch.messages[0].kind shouldBe MessageKind.MEDIA
        batch.messages[0].media.shouldNotBeNull().uri shouldBe "content://fb/1"
    }

    test("a named sent you a message body is treated as a hidden preview") {
        val shape = Fixtures.bigText("Cara", "Cara sent you a message")
        val batch = parser.parse(fbSnapshot(shape))
        batch.messages[0].contentStatus shouldBe ContentStatus.PREVIEW_RESTRICTED_SUSPECTED
        batch.warnings shouldContain ParseWarning.PREVIEW_PLACEHOLDER
        standard.parse(fbSnapshot(shape)).contentStatus shouldBe ContentStatus.NOTIFICATION_TEXT
    }

    test("a call notification yields zero messages") {
        val shape = Fixtures.base("Cara", "Incoming call", category = "call")
        val batch = parser.parse(fbSnapshot(shape))
        batch.messages.shouldBeEmpty()
        batch.warnings shouldContain ParseWarning.POSSIBLE_SYSTEM_NOTICE
        standard.parse(fbSnapshot(shape)).messages shouldHaveSize 1
    }

    test("a bare summary line becomes a summary observation") {
        val batch = parser.parse(fbSnapshot(Fixtures.bigText("Messenger", "3 new messages")))
        batch.messages.shouldBeEmpty()
        batch.contentStatus shouldBe ContentStatus.SUMMARY_ONLY
        batch.summary.shouldNotBeNull().messageCount shouldBe 3
    }

    test("an unmodelled template falls back to the standard behaviour with a warning") {
        val shape = Fixtures.base("Cara", "hey").copy(template = NotificationTemplate.UNKNOWN)
        parser.parse(fbSnapshot(shape)).warnings shouldContain ParseWarning.ADAPTER_FALLBACK_TO_STANDARD
    }
})
