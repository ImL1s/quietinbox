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

class ReconcilerReviewFixesTest : FunSpec({
    val r = Reconciler()
    val noIds: (String) -> KnownMessage? = { null }

    test("stale replay [A] after [A,B,C] keeps the checkpoint window so [B,C,D] still aligns") {
        val w = window("A", "B", "C")
        val stale = r.reconcile("k1", listOf(msg("A")), w, noIds)
        stale.notes shouldContain ReconcileNote.WINDOW_KEPT
        stale.newWindow.items.map { it.fingerprint } shouldBe w.items.map { it.fingerprint }
        val next = r.reconcile("k1", listOf(msg("B", 0), msg("C", 1), msg("D", 2)), stale.newWindow, noIds)
        next.decisions.map { it::class.simpleName } shouldBe listOf("Known", "Known", "New")
        (next.decisions[0] as Decision.Known).existingMessageId shouldBe 101L
    }

    test("id items inside the window do not shift positional alignment of id-less items") {
        // Previous window: [A, id1, B]; new window: [A, id1, B, C] with id1 known.
        val prev = MessageWindow(
            "k1",
            listOf(
                WindowItem(Fingerprint.of(msg("A", 0)), null, 10L),
                WindowItem(Fingerprint.of(msg("X", 1, id = "id1")), "id1", 11L),
                WindowItem(Fingerprint.of(msg("B", 2)), null, 12L),
            ),
            closed = false,
        )
        val known: (String) -> KnownMessage? = { if (it == "id1") KnownMessage(11L, "fp", "X") else null }
        val s = r.reconcile("k1", listOf(msg("A", 0), msg("X", 1, id = "id1"), msg("B", 2), msg("C", 3)), prev, known)
        s.decisions.map { it::class.simpleName } shouldBe listOf("Known", "Known", "Known", "New")
        (s.decisions[0] as Decision.Known).existingMessageId shouldBe 10L
        (s.decisions[1] as Decision.Known).kind shouldBe KnownKind.SAME_ID
        (s.decisions[2] as Decision.Known).existingMessageId shouldBe 12L
    }

    test("new window items carry their decision index for id mapping") {
        val s = r.reconcile("k1", listOf(msg("A", 0), msg("B", 1)), null, noIds)
        s.newWindow.items.map { it.decisionIndex } shouldBe listOf(0, 1)
    }
})

class ReconcilerResyncTest : FunSpec({
    val r = Reconciler()
    val noIds: (String) -> KnownMessage? = { null }

    test("active-notification resync of the same post after a reconnect is a repost, not ambiguous") {
        val first = r.reconcile("k1", listOf(msg("好")), null, noIds, postedAtEpochMs = 5_000)
        val closed = first.newWindow.copy(closed = true, items = first.newWindow.items.map { it.copy(messageId = 9L) })
        val resync = r.reconcile("k1", listOf(msg("好")), closed, noIds, postedAtEpochMs = 5_000)
        resync.decisions[0].shouldBeInstanceOf<Decision.Known>().kind shouldBe KnownKind.REPOST
        // A genuinely new post (new post time) of the same single text stays ambiguous.
        val newPost = r.reconcile("k1", listOf(msg("好")), closed, noIds, postedAtEpochMs = 6_000)
        newPost.decisions[0].shouldBeInstanceOf<Decision.AmbiguousRepeat>()
    }
})

class ReconcilerWindowKeptTest : FunSpec({
    val r = Reconciler()
    val noIds: (String) -> KnownMessage? = { null }

    test("a kept window carries the previous message ids and no decision indices") {
        val kept = r.reconcile("k2", listOf(msg("B")), window("A", "B", "C"), noIds)
        kept.notes shouldContain ReconcileNote.WINDOW_KEPT
        kept.newWindow.items.map { it.messageId } shouldBe listOf(100L, 101L, 102L)
        kept.newWindow.items.all { it.decisionIndex == null } shouldBe true
        kept.newWindow.notificationKey shouldBe "k2"
        // The next real post under the new key still aligns against the kept content.
        val next = r.reconcile("k2", listOf(msg("C", 0), msg("D", 1)), kept.newWindow, noIds)
        next.decisions.map { it::class.simpleName } shouldBe listOf("Known", "New")
        (next.decisions[0] as Decision.Known).existingMessageId shouldBe 102L
    }
})

class ReconcilerAmbiguousKeepTest : FunSpec({
    val r = Reconciler()
    val noIds: (String) -> KnownMessage? = { null }

    test("an ambiguous single repeat after a closed window keeps the window, so the next post cannot duplicate") {
        // Same key, closed by onRemoved, a NEW post time, one item equal to the old tail.
        val closed = window("A", "B", "C", closed = true).copy(postedAtEpochMs = 5_000)
        val repeat = r.reconcile("k1", listOf(msg("C")), closed, noIds, postedAtEpochMs = 6_000)
        repeat.decisions shouldHaveSize 1
        repeat.decisions[0].shouldBeInstanceOf<Decision.AmbiguousRepeat>().existingMessageId shouldBe 102L
        repeat.notes shouldContain ReconcileNote.WINDOW_KEPT
        repeat.newWindow.items.map { it.messageId } shouldBe listOf(100L, 101L, 102L)
        repeat.newWindow.postedAtEpochMs shouldBe 6_000L
        // The next real update aligns against the kept content: B and C are known, only D is new.
        val next = r.reconcile("k1", listOf(msg("B", 0), msg("C", 1), msg("D", 2)), repeat.newWindow, noIds, postedAtEpochMs = 7_000)
        next.decisions.map { it::class.simpleName } shouldBe listOf("Known", "Known", "New")
        (next.decisions[0] as Decision.Known).existingMessageId shouldBe 101L
        (next.decisions[1] as Decision.Known).existingMessageId shouldBe 102L
    }

    test("an ambiguous repeat of a single-item window still records the new post time") {
        val closed = window("C", closed = true).copy(postedAtEpochMs = 5_000)
        val repeat = r.reconcile("k1", listOf(msg("C")), closed, noIds, postedAtEpochMs = 6_000)
        repeat.decisions[0].shouldBeInstanceOf<Decision.AmbiguousRepeat>()
        repeat.newWindow.items shouldHaveSize 1
        repeat.newWindow.postedAtEpochMs shouldBe 6_000L
    }
})
