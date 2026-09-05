package dev.quietinbox.core.reconcile

import dev.quietinbox.core.model.Limits
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.TimestampQuality
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun msg(body: String, ordinal: Int = 0, ts: Long? = null, id: String? = null, sender: String = "A") = MessageCandidate(
    ordinal = ordinal,
    body = body,
    sender = SenderCandidate(displayName = sender),
    sourceMessageId = id,
    sourceTimestampEpochMs = ts,
    timestampQuality = if (ts != null) TimestampQuality.SOURCE_MESSAGE else TimestampQuality.OBSERVED_ONLY,
)

private fun window(vararg bodies: String, key: String = "k1", closed: Boolean = false) = MessageWindow(
    key,
    bodies.mapIndexed { i, b -> WindowItem(Fingerprint.of(msg(b, i)), null, 100L + i) },
    closed,
)

class ReconcilerTest : FunSpec({
    val r = Reconciler()
    val noIds: (String) -> KnownMessage? = { null }

    test("[A] -> [A,B] -> [A,B,C] yields exactly A B C") {
        val s1 = r.reconcile("k1", listOf(msg("A")), null, noIds)
        s1.decisions.map { it::class.simpleName } shouldBe listOf("New")
        val w1 = s1.newWindow.copy(items = s1.newWindow.items.mapIndexed { i, it -> it.copy(messageId = 1L + i) })

        val s2 = r.reconcile("k1", listOf(msg("A", 0), msg("B", 1)), w1, noIds)
        s2.decisions[0].shouldBeInstanceOf<Decision.Known>().kind shouldBe KnownKind.REPOST
        s2.decisions[1].shouldBeInstanceOf<Decision.New>()
        val w2 = s2.newWindow.copy(items = s2.newWindow.items.mapIndexed { i, it -> it.copy(messageId = 1L + i) })

        val s3 = r.reconcile("k1", listOf(msg("A", 0), msg("B", 1), msg("C", 2)), w2, noIds)
        s3.decisions.filterIsInstance<Decision.New>().map { it.candidate.body } shouldBe listOf("C")
    }

    test("only [A,B,C] received stores all three, not just C") {
        val s = r.reconcile("k1", listOf(msg("A", 0), msg("B", 1), msg("C", 2)), null, noIds)
        s.decisions.filterIsInstance<Decision.New>() shouldHaveSize 3
        s.notes shouldContain ReconcileNote.NO_PREVIOUS_WINDOW
    }

    test("[A,B,C] -> [B,C,D] keeps A B C D") {
        val s = r.reconcile("k1", listOf(msg("B", 0), msg("C", 1), msg("D", 2)), window("A", "B", "C"), noIds)
        s.decisions.map { it::class.simpleName } shouldBe listOf("Known", "Known", "New")
        (s.decisions[2] as Decision.New).candidate.body shouldBe "D"
    }

    test("[好(id=1), 好(id=2)] are two messages") {
        val s = r.reconcile("k1", listOf(msg("好", 0, id = "1"), msg("好", 1, id = "2")), null, noIds)
        s.decisions.filterIsInstance<Decision.New>() shouldHaveSize 2
        s.decisions.all { (it as Decision.New).confirmedById } shouldBe true
    }

    test("[好(?)] -> [好(?)] from a new post is an ambiguous observation, not a second message and not dropped") {
        val s1 = r.reconcile("k1", listOf(msg("好")), null, noIds)
        val w1 = s1.newWindow.copy(closed = true, items = s1.newWindow.items.map { it.copy(messageId = 7L) })
        val s2 = r.reconcile("k2", listOf(msg("好")), w1, noIds)
        s2.decisions shouldHaveSize 1
        val d = s2.decisions[0].shouldBeInstanceOf<Decision.AmbiguousRepeat>()
        d.existingMessageId shouldBe 7L
    }

    test("identical single item re-posted under the same open notification is a repost, not ambiguous") {
        val s1 = r.reconcile("k1", listOf(msg("好")), null, noIds)
        val s2 = r.reconcile("k1", listOf(msg("好")), s1.newWindow, noIds)
        s2.decisions[0].shouldBeInstanceOf<Decision.Known>().kind shouldBe KnownKind.REPOST
    }

    test("identical bodies with distinct source timestamps are distinct messages") {
        val s1 = r.reconcile("k1", listOf(msg("好", ts = 1_000)), null, noIds)
        val w1 = s1.newWindow.copy(closed = true)
        val s2 = r.reconcile("k2", listOf(msg("好", ts = 2_000)), w1, noIds)
        s2.decisions[0].shouldBeInstanceOf<Decision.New>()
    }

    test("[A,B,C] then old [A] does not delete B C and does not duplicate A") {
        val s = r.reconcile("k1", listOf(msg("A")), window("A", "B", "C"), noIds)
        s.decisions shouldHaveSize 1
        s.decisions[0].shouldBeInstanceOf<Decision.Known>().kind shouldBe KnownKind.STALE_WINDOW
        s.notes shouldContain ReconcileNote.STALE_REPLAY
    }

    test("multiplicity inside one window is preserved") {
        val s = r.reconcile("k1", listOf(msg("ok", 0), msg("ok", 1)), null, noIds)
        s.decisions.filterIsInstance<Decision.New>() shouldHaveSize 2
    }

    test("same id with changed body is a revision") {
        val known: (String) -> KnownMessage? = { if (it == "m1") KnownMessage(42, "fp", "old") else null }
        val s = r.reconcile("k1", listOf(msg("new", id = "m1")), null, known)
        s.decisions[0].shouldBeInstanceOf<Decision.Revision>().existingMessageId shouldBe 42
    }

    test("oversized windows degrade instead of blocking") {
        val many = (0 until 200).map { msg("m$it", it) }
        val s = r.reconcile("k1", many, null, noIds)
        s.degraded shouldBe true
        s.newWindow.items.size shouldBe Limits.MAX_WINDOW_ITEMS
    }
})
