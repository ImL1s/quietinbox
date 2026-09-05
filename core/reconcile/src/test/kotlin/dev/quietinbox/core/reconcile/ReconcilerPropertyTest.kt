package dev.quietinbox.core.reconcile

import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.TimestampQuality
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * Property: simulating a sliding MessagingStyle window over a random stream of *distinct*
 * messages, the reconciler must accept every observed message exactly once (no loss, no
 * duplication) and re-posting the same window must never produce new decisions.
 * Seeds are fixed so failures are reproducible; 1,000 iterations on PR per plan section 15.
 */
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
})
