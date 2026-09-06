package dev.quietinbox.platform.storage.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * The gate every vault writer goes through (QI-SEC-003). Real coroutines, no virtual time: the
 * properties under test are orderings, which a test dispatcher would only make look deterministic.
 */
class VaultMaintenanceTest : FunSpec({

    test("work runs and returns its value when no maintenance is active") {
        val gate = VaultMaintenance()
        gate.work { 41 + 1 } shouldBe 42
        gate.isActive shouldBe false
    }

    test("work is refused (null, block not run) while an exclusive run is active") {
        val gate = VaultMaintenance()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exclusive = launch { gate.exclusive { inside.complete(Unit); release.await() } }
        withTimeout(5_000) { inside.await() }

        var ran = false
        gate.work { ran = true; 1 }.shouldBeNull()
        ran shouldBe false
        gate.isActive shouldBe true

        release.complete(Unit)
        exclusive.join()
        gate.isActive shouldBe false
        gate.work { 2 } shouldBe 2
    }

    test("an exclusive run cancels the work in flight and waits for it before running") {
        val gate = VaultMaintenance()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val worker = async {
            gate.work {
                started.complete(Unit)
                never.await() // only a cancellation gets us out of here
            }
        }
        withTimeout(5_000) { started.await() }

        var workerStillRunning = true
        worker.invokeOnCompletion { workerStillRunning = false }
        val ran = gate.exclusive { workerStillRunning }
        // By the time the exclusive block ran, the worker had been cancelled and joined.
        ran shouldBe false
        val failure = shouldThrow<CancellationException> { worker.await() }
        failure.shouldBeInstanceOf<MaintenanceCancellation>()
    }

    test("exclusive runs are serialised and the pipeline lock is held for the whole run") {
        val gate = VaultMaintenance()
        val order = ArrayList<String>()
        val firstInside = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val first = launch {
            gate.exclusive {
                order += "first-start"
                firstInside.complete(Unit)
                firstRelease.await()
                order += "first-end"
            }
        }
        withTimeout(5_000) { firstInside.await() }
        val second = launch { gate.exclusive { order += "second" } }
        // Someone waiting for the pipeline lock (the capture pipeline) waits too.
        val pipeline = launch { gate.pipelineMutex.withLock { order += "pipeline" } }
        repeat(10) { yield() }
        delay(100)
        order shouldBe listOf("first-start")

        firstRelease.complete(Unit)
        first.join()
        second.join()
        pipeline.join()
        order.first() shouldBe "first-start"
        order[1] shouldBe "first-end"
        order.drop(2).toSet() shouldBe setOf("second", "pipeline")
    }
})
