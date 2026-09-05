package dev.quietinbox.core.identity

import dev.quietinbox.core.model.Confidence
import dev.quietinbox.core.model.IdentityConfidence
import dev.quietinbox.core.model.IdentityEvidence
import dev.quietinbox.core.model.IdentityEvidenceKind
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class IdentityResolverTest : FunSpec({
    val parser = StandardParser()
    val resolver = IdentityResolver()

    test("verified chat id wins and is scoped") {
        val snap = Fixtures.snapshot(Fixtures.bigText("Alice", "hi", shortcutId = "sc"))
        val batch = parser.parse(snap).let {
            it.copy(identityEvidence = it.identityEvidence + IdentityEvidence(IdentityEvidenceKind.SOURCE_CHAT_ID, "c123", Confidence.VERIFIED))
        }
        val id = resolver.resolve(snap, batch)
        id.identityKey shouldBe "chat:c123"
        id.confidence shouldBe IdentityConfidence.VERIFIED_SOURCE_ID
        id.scope shouldBe snap.source
    }

    test("shortcut id is inferred, not verified") {
        val snap = Fixtures.snapshot(Fixtures.bigText("Alice", "hi", shortcutId = "sc"))
        val id = resolver.resolve(snap, parser.parse(snap))
        id.identityKey shouldBe "shortcut:sc"
        id.confidence shouldBe IdentityConfidence.INFERRED_FROM_STREAM
    }

    test("same display name in different tagged streams stays separate") {
        val a = Fixtures.snapshot(Fixtures.bigText("Alice", "hi", tag = "chat-1"))
        val b = Fixtures.snapshot(Fixtures.bigText("Alice", "hi", tag = "chat-2"))
        val ia = resolver.resolve(a, parser.parse(a))
        val ib = resolver.resolve(b, parser.parse(b))
        ia.identityKey shouldNotBe ib.identityKey
        ia.confidence shouldBe IdentityConfidence.INFERRED_FROM_STREAM
    }

    test("title only falls back to UNRESOLVED") {
        val snap = Fixtures.snapshot(Fixtures.bigText("Alice", "hi", id = 1))
        val id = resolver.resolve(snap, parser.parse(snap))
        id.identityKey shouldBe "title:Alice"
        id.confidence shouldBe IdentityConfidence.UNRESOLVED
    }

    test("different profiles never share identity") {
        val snap = Fixtures.snapshot(Fixtures.bigText("Alice", "hi", shortcutId = "sc"))
        val other = snap.copy(source = snap.source.copy(profileKey = "user:10"))
        resolver.resolve(snap, parser.parse(snap)).scope shouldNotBe resolver.resolve(other, parser.parse(other)).scope
    }
})
