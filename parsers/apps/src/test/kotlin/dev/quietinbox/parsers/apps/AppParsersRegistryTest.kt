package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.Confidence
import dev.quietinbox.core.model.IdentityEvidenceKind
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.parser.ParserRegistry
import dev.quietinbox.core.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class AppParsersRegistryTest : FunSpec({
    val registry = ParserRegistry(AppParsers.all())

    val routes = listOf(
        KnownSources.LINE to "line",
        KnownSources.WHATSAPP to "whatsapp",
        KnownSources.TELEGRAM to "telegram",
        KnownSources.INSTAGRAM to "instagram",
        KnownSources.MESSENGER to "messenger",
    )

    test("every known source routes to its own adapter") {
        val shape = Fixtures.bigText("Someone", "hello")
        routes.forEach { (packageName, parserId) ->
            registry.parserFor(Fixtures.snapshot(shape, packageName = packageName)).id shouldBe parserId
        }
    }

    test("an unknown package falls through to the standard parser") {
        val shape = Fixtures.bigText("Someone", "hello")
        registry.parserFor(Fixtures.snapshot(shape, packageName = "com.example.chat")).id shouldBe "standard"
    }

    test("all() exposes the five adapters at version 0.1.0 and each declares one package") {
        val all = AppParsers.all()
        all shouldHaveSize 5
        all.map { it.id } shouldBe routes.map { it.second }
        all.all { it.version == "0.1.0" } shouldBe true
        all.all { it.packages.size == 1 } shouldBe true
    }

    test("no adapter ever claims a source message id or a verified chat id") {
        val shapes: List<NotificationShape> = listOf(
            Fixtures.messaging(conversationTitle = "Group", isGroup = true, shortcutId = "sc-1") {
                message("Ann", "one", 1_000)
                message("Ben", "two", 2_000)
            },
            Fixtures.inbox("Group", listOf("Ann: one", "Ben: two")),
            Fixtures.bigText("Ann", "Ann: hello"),
            Fixtures.bigText("Ann", "sent a photo"),
            Fixtures.summary("App", "5 new messages from 2 chats"),
            Fixtures.base("Ann", "Sending..."),
        )
        AppParsers.all().forEach { parser ->
            val packageName = parser.packages.first()
            shapes.forEach { shape ->
                val batch = parser.parse(Fixtures.snapshot(shape, packageName = packageName))
                batch.messages.all { it.sourceMessageId == null } shouldBe true
                batch.identityEvidence.none {
                    it.kind == IdentityEvidenceKind.SOURCE_CHAT_ID || it.confidence == Confidence.VERIFIED
                } shouldBe true
                batch.conversation?.key?.let { key ->
                    (key is dev.quietinbox.core.model.ConversationKey.SourceChatId) shouldBe false
                }
            }
        }
    }
})
