package dev.quietinbox.feature.analytics

import dev.quietinbox.core.analytics.ObservedMessage
import dev.quietinbox.core.analytics.PeriodKind
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.AnalyticsRepository
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.InboxCounts
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * State rules of the analytics pipeline, with the repositories mocked. The pipeline itself runs on
 * the real `Dispatchers.Default` so the "off the main thread" property is observed, not assumed
 * (the `flowOn` had been deleted once by a refactor and nothing caught it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest : FunSpec({
    beforeSpec { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    afterSpec { Dispatchers.resetMain() }

    fun message(id: Long, at: Long) = ObservedMessage(
        conversationId = id, packageName = "demo.pkg", timestampEpochMs = at,
        dedupState = DedupState.CONFIRMED, contentStatus = ContentStatus.FULL_STRUCTURED,
        body = "hello $id", senderName = "Ann", isSelf = false,
    )

    /** Mocked storage around a real [AnalyticsViewModel]; [gate] can hold the message query open. */
    class Harness(initialVault: VaultState, countsFlow: kotlinx.coroutines.flow.Flow<InboxCounts>? = null) {
        val vaultState = MutableStateFlow(initialVault)
        val counts = MutableStateFlow(InboxCounts(1, 1, 0, 0))
        var messages: List<ObservedMessage> = listOf(message(1, System.currentTimeMillis() - 60_000))
        var gate = CompletableDeferred(Unit)
        val queries = AtomicInteger()
        val queryThreads = CopyOnWriteArrayList<String>()
        val analytics = mockk<AnalyticsRepository> {
            coEvery { messagesBetween(any(), any(), any()) } coAnswers {
                queryThreads += Thread.currentThread().name
                queries.incrementAndGet()
                gate.await()
                messages
            }
            coEvery { summaryCountBetween(any(), any()) } returns 0
            coEvery { labels(any()) } returns emptyMap()
            coEvery { earliestTimestamp() } returns null
        }
        val health = mockk<HealthRepository> { every { observeGaps(any()) } returns flowOf(emptyList()) }
        // Mirrors DatabaseHolder.flowWithDb: nothing is observable while the vault is not Ready.
        val inbox = mockk<InboxRepository> {
            every { observeCounts() } returns this@Harness.vaultState.flatMapLatest { v -> if (v is VaultState.Ready) (countsFlow ?: counts) else emptyFlow() }
        }
        val vault = mockk<VaultRepository> { every { state } returns vaultState }
        val vm = AnalyticsViewModel(analytics, health, inbox, vault)
    }

    fun ready(): VaultState = VaultState.Ready(mockk<QuietInboxDatabase>(relaxed = true))

    test("first report arrives, carries no stale label, and is computed off the main thread") {
        val h = Harness(ready())
        val testThread = Thread.currentThread().name
        val s = withTimeout(10_000) { h.vm.state.first { !it.loading } }
        s.report.shouldNotBeNull().sampleSize shouldBe 1
        s.capped.shouldBeFalse()
        s.vaultLocked.shouldBeFalse()
        h.queryThreads.shouldNotBeEmpty()
        // Without flowOn(Dispatchers.Default) the query would run on the collector's (main/test) thread.
        h.queryThreads.first() shouldNotBe testThread
    }

    test("a period switch shows a clean loading placeholder: no report and no capped label from the previous period") {
        val h = Harness(ready())
        h.messages = List(AnalyticsRepository.MESSAGE_CAP) { message(it.toLong() % 5, System.currentTimeMillis() - it * 1_000L) }
        val capped = withTimeout(20_000) { h.vm.state.first { !it.loading } }
        capped.capped.shouldBeTrue()

        h.gate = CompletableDeferred() // hold the next query open so the placeholder is observable
        h.messages = listOf(message(1, System.currentTimeMillis() - 60_000))
        h.vm.setPeriod(PeriodKind.ALL)
        val placeholder = withTimeout(10_000) { h.vm.state.first { it.selection.kind == PeriodKind.ALL } }
        placeholder.loading.shouldBeTrue()
        placeholder.capped.shouldBeFalse()
        placeholder.report.shouldBeNull()

        h.gate.complete(Unit)
        val done = withTimeout(10_000) { h.vm.state.first { it.selection.kind == PeriodKind.ALL && !it.loading } }
        done.capped.shouldBeFalse()
        done.report.shouldNotBeNull().sampleSize shouldBe 1
    }

    test("a vault change recomputes quietly, without a loading state") {
        val h = Harness(ready())
        val collector = CoroutineScope(Dispatchers.Default).launch { h.vm.state.collect {} }
        try {
            withTimeout(10_000) { h.vm.state.first { !it.loading } }
            val before = h.queries.get()
            h.gate = CompletableDeferred() // hold the recompute open at its first query
            h.counts.value = InboxCounts(2, 2, 0, 0)
            withTimeout(10_000) { while (h.queries.get() <= before) delay(20) }
            // The recompute is in flight and blocked before it can emit: a loading placeholder, had one
            // been emitted, would be the current state right now.
            h.vm.state.value.loading.shouldBeFalse()
            h.vm.state.value.report.shouldNotBeNull()
            h.gate.complete(Unit)
            withTimeout(10_000) { h.vm.state.first { !it.loading && it.report != null } }
        } finally {
            collector.cancel()
        }
    }

    test("a locked vault is shown as locked, never as an endless spinner, and recovers once unlocked") {
        val h = Harness(VaultState.Locked(KeyFailure.Invalidated))
        val locked = withTimeout(10_000) { h.vm.state.first { !it.loading } }
        locked.vaultLocked.shouldBeTrue()
        locked.report.shouldBeNull()

        h.vaultState.value = ready() // no count change: unlocking alone must recover the page
        val recovered = withTimeout(10_000) { h.vm.state.first { it.report != null } }
        recovered.vaultLocked.shouldBeFalse()
    }

    test("a vault that locks while the page is open recovers when unlocked, even without a count change") {
        val h = Harness(ready())
        withTimeout(10_000) { h.vm.state.first { it.report != null } }
        h.vaultState.value = VaultState.Locked(KeyFailure.Invalidated)
        withTimeout(10_000) { h.vm.state.first { it.vaultLocked } }
        h.vaultState.value = ready() // the counts are unchanged, so no count tick will arrive
        val recovered = withTimeout(10_000) { h.vm.state.first { it.report != null && !it.vaultLocked } }
        recovered.loading.shouldBeFalse()
    }

    test("an opening vault keeps the loading state and computes once ready") {
        val h = Harness(VaultState.Opening)
        withTimeoutOrNull(700) { h.vm.state.first { !it.loading } }.shouldBeNull()
        h.vaultState.value = ready() // no count change: becoming Ready alone must compute
        val s = withTimeout(10_000) { h.vm.state.first { !it.loading } }
        s.report.shouldNotBeNull()
    }

    test("a failing count query does not leave the page loading") {
        val h = Harness(ready(), countsFlow = flow { throw IllegalStateException("db") })
        val s = withTimeout(10_000) { h.vm.state.first { !it.loading } }
        s.report.shouldNotBeNull()
        s.degraded.shouldBeFalse() // the report itself was computed from healthy queries
    }

    test("a failing query marks the report as degraded instead of passing it off as complete") {
        val h = Harness(ready())
        coEvery { h.analytics.labels(any()) } throws IllegalStateException("db")
        val s = withTimeout(10_000) { h.vm.state.first { !it.loading } }
        s.report.shouldNotBeNull()
        s.degraded.shouldBeTrue()
    }
})
