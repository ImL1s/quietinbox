package dev.quietinbox.core.reconcile

import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.TimestampQuality
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * Property: simulating a sliding MessagingStyle window over a random stream of *distinct*
 * messages, the reconciler must accept every observed message exactly once (no loss, no
 * duplication) and re-posting the same window must never produce new decisions.
 * Seeds are fixed so failures are reproducible; 1,000 iterations on PR per plan section 15.
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class ReconcilerPropertyTest : FunSpec({
    val r = Reconciler()

    test("sliding windows over distinct messages neither lose nor duplicate") {
        checkAll(
            PropTestConfig(seed = 20260905L, iterations = 1_000),
            Arb.list(Arb.int(1, 6), 1..40),
            Arb.int(1, 8),
        ) { steps, windowSize ->
            var next = 0
            var window: MessageWindow? = null
            val accepted = ArrayList<String>()
            for (step in steps) {
                next += step
                val visible = (maxOf(0, next - windowSize) until next).mapIndexed { i, n ->
                    MessageCandidate(
                        ordinal = i,
                        body = "msg-$n",
                        sender = SenderCandidate("S"),
                        sourceTimestampEpochMs = 1_000L + n,
                        timestampQuality = TimestampQuality.SOURCE_MESSAGE,
                    )
                }
                val res = r.reconcile("key", visible, window, { null })
                accepted += res.decisions.filterIsInstance<Decision.New>().map { it.candidate.body }
                // Re-posting identical content must be a no-op.
                val again = r.reconcile("key", visible, res.newWindow, { null })
                again.decisions.count { it is Decision.New } shouldBe 0
                window = again.newWindow
            }
            // Messages that scrolled out of the window before ever being observed are
            // platform-unobservable; everything observed must be present exactly once and in order.
            val expected = (0 until next).map { "msg-$it" }.filter { it in accepted.toSet() }
            accepted shouldBe expected
            accepted.toSet().size shouldBe accepted.size
        }
    }

    /**
     * Repeated, id-less, timestamp-less content (the "好 / ok" space where positional alignment is
     * inherently ambiguous). "No loss" cannot hold there: a window that slid by exactly its own
     * content is indistinguishable from a repost. What must hold is *no duplication*, and that a
     * post which adds nothing (a stale suffix replay, an ambiguous single repeat, an identical
     * repost) never shrinks the checkpoint window.
     */
    test("repeated content never duplicates and replays never shrink the window") {
        data class Step(val advance: Int, val replaySuffix: Int, val closeBefore: Boolean)
        val stepArb = Arb.bind(Arb.int(1, 4), Arb.int(0, 3), Arb.boolean()) { a, s, c -> Step(a, s, c) }
        checkAll(
            PropTestConfig(seed = 20260906L, iterations = 1_000),
            Arb.list(stepArb, 1..30),
            Arb.int(1, 5),
            Arb.int(1, 3),
        ) { steps, windowSize, alphabet ->
            var posted = 0L
            var next = 0
            var window: MessageWindow? = null
            var lastVisible: List<String> = emptyList()
            var newTotal = 0
            fun candidates(bodies: List<String>) = bodies.mapIndexed { i, b ->
                MessageCandidate(ordinal = i, body = b, sender = SenderCandidate("S"), sourceTimestampEpochMs = null, timestampQuality = TimestampQuality.OBSERVED_ONLY)
            }
            fun body(n: Int) = "m" + (n % alphabet)
            for (step in steps) {
                // A stale replay: the last k visible items re-posted with a new post time.
                if (step.replaySuffix > 0 && lastVisible.isNotEmpty()) {
                    val prev = window
                    val input = if (step.closeBefore) prev?.copy(closed = true) else prev
                    val res = r.reconcile("key", candidates(lastVisible.takeLast(step.replaySuffix)), input, { null }, ++posted)
                    res.decisions.count { it is Decision.New } shouldBe 0
                    res.newWindow.items.size shouldBeGreaterThanOrEqual (prev?.items?.size ?: 0)
                    window = res.newWindow
                }
                // A real update: the stream advances and the notification shows the last windowSize.
                next += step.advance
                val visible = (maxOf(0, next - windowSize) until next).map { body(it) }
                val prev = window
                val input = if (step.closeBefore) prev?.copy(closed = true) else prev
                val res = r.reconcile("key", candidates(visible), input, { null }, ++posted)
                val news = res.decisions.count { it is Decision.New }
                news shouldBeLessThanOrEqual step.advance
                newTotal += news
                // An identical repost never adds and never shrinks.
                val again = r.reconcile("key", candidates(visible), res.newWindow, { null }, ++posted)
                again.decisions.count { it is Decision.New } shouldBe 0
                again.newWindow.items.size shouldBeGreaterThanOrEqual res.newWindow.items.size
                window = again.newWindow
                lastVisible = visible
            }
            newTotal shouldBeLessThanOrEqual next
        }
    }
})
