package dev.quietinbox.platform.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.db.VaultUnavailableException
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.storage.repo.CommitOutcome
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.storage.settings.AppSettings
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections

private const val APP_PKG = "dev.quietinbox.app"
private const val ENABLED_PKG = "com.example.chat"
private const val UNLISTED_PKG = "com.example.other"
private const val SESSION_ID = 7L

/**
 * Pipeline behaviour of the capture singleton: the generation commit fence, the resume path and
 * cold-start source filtering.
 *
 * The coordinator owns a real `CoroutineScope` on `Dispatchers.Default`, so these tests never
 * assume an ordering the production code does not guarantee. Every hand-off is either a latch
 * inside a stubbed repository call or a poll on the observable status; the queue is drained by a
 * single consumer coroutine, so "event B was journaled" is a happens-after barrier for event A.
 */
class CaptureCoordinatorTest : FunSpec({

    /** Polls [check] until it holds; fails with the caller's assertion if the deadline passes. */
    suspend fun awaitUntil(timeoutMs: Long = 5_000, check: suspend () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                check()
                return
            } catch (e: Throwable) {
                if (e is CancellationException || System.currentTimeMillis() > deadline) throw e
                delay(10)
            }
        }
    }

    /** Asserts [check] still holds after the pipeline has had time to do something wrong. */
    suspend fun stillHolds(forMs: Long = 300, check: suspend () -> Unit) {
        check()
        delay(forMs)
        check()
    }

    fun sourceConfig(packageName: String, enabled: Boolean = true, paused: Boolean = false) =
        SourceConfiguration(
            packageName = packageName, displayName = packageName, enabled = enabled, paused = paused,
            retentionDays = null, mediaEnabled = true, addedAtEpochMs = 0L, adapterId = null,
        )

    /**
     * A snapshot with no title and no text: the standard parser produces no candidates, so the
     * pipeline stops at "journaled, then SKIPPED" and these tests never depend on parser,
     * identity or reconciler behaviour.
     */
    fun captured(eventId: String, pkg: String = ENABLED_PKG, origin: CaptureOrigin = CaptureOrigin.LIVE) =
        CapturedNotification(
            Fixtures.snapshot(
                shape = Fixtures.base(title = null, text = null),
                packageName = pkg,
                eventId = eventId,
                origin = origin,
            ),
            null,
        )

    /**
     * Every collaborator is a relaxed mock: the coordinator runs its bookkeeping inside `guarded
     * {}`, which swallows the exception a strict mock would raise, so a missing stub would fail
     * silently. The two return values that actually steer control flow are stubbed explicitly.
     */
    class Harness {
        val context: Context = mockk(relaxed = true)
        val ingest: IngestRepository = mockk(relaxed = true)
        val sources: SourceRepository = mockk(relaxed = true)
        val health: HealthRepository = mockk(relaxed = true)
        val settings: SettingsRepository = mockk(relaxed = true)
        val vault: VaultRepository = mockk(relaxed = true)
        val mediaCopier: MediaCopier = mockk(relaxed = true)
        val listenerAccess: ListenerAccess = mockk(relaxed = true)
        val service: NotificationListenerService = mockk(relaxed = true)
        val maintenance = VaultMaintenance()

        val observedSources = MutableSharedFlow<List<SourceConfiguration>>(replay = 1)
        val vaultState = MutableStateFlow<VaultState>(VaultState.Opening)

        /** Event ids that reached [IngestRepository.journal], in the order the consumer saw them. */
        val journaled: MutableList<String> = Collections.synchronizedList(ArrayList())

        init {
            every { context.packageName } returns APP_PKG
            every { listenerAccess.isGranted() } returns true
            every { sources.observeSources() } returns observedSources
            every { vault.state } returns vaultState
            every { service.activeNotifications } returns null
            coEvery { settings.current() } returns AppSettings()
            coEvery { health.startSession(any(), any(), any()) } returns SESSION_ID
            coEvery { sources.sources() } returns listOf(
                SourceConfiguration(ENABLED_PKG, ENABLED_PKG, true, false, null, true, 0L, null),
            )
            journalAnswers { true }
        }

        /** Replaces the journal stub; [answer] runs on the consumer coroutine. */
        fun journalAnswers(answer: suspend (NotificationSnapshot) -> Boolean) {
            coEvery { ingest.journal(any(), any(), any()) } coAnswers {
                val snapshot = firstArg<NotificationSnapshot>()
                journaled += snapshot.eventId
                answer(snapshot)
            }
        }

        /** The source list the coordinator reloads under the pipeline lock; tests mutate it to flip a policy. */
        val sourceList: MutableList<SourceConfiguration> = Collections.synchronizedList(mutableListOf(sourceConfig(ENABLED_PKG)))

        init {
            coEvery { sources.sources() } answers { sourceList.toList() }
            coEvery { sources.setEnabled(any(), any()) } coAnswers {
                val pkg = firstArg<String>()
                val enabled = secondArg<Boolean>()
                val i = sourceList.indexOfFirst { it.packageName == pkg }
                if (i >= 0) sourceList[i] = sourceList[i].copy(enabled = enabled)
            }
            coEvery { sources.setPaused(any(), any()) } coAnswers {
                val pkg = firstArg<String>()
                val paused = secondArg<Boolean>()
                val i = sourceList.indexOfFirst { it.packageName == pkg }
                if (i >= 0) sourceList[i] = sourceList[i].copy(paused = paused)
            }
        }

        fun coordinator() = CaptureCoordinator(context, ingest, sources, health, settings, vault, mediaCopier, listenerAccess, maintenance)
    }

    /** Returns once the connect coroutine has finished, so `sessionId` is set. */
    suspend fun Harness.awaitConnected() = awaitUntil { coVerify { ingest.closeAllWindows(any()) } }

    test("an event queued before a pause is never committed after it") {
        val h = Harness()
        val enteredJournal = CompletableDeferred<Unit>()
        val releaseJournal = CompletableDeferred<Unit>()
        h.journalAnswers { snapshot ->
            if (snapshot.eventId == "evt-a") {
                enteredJournal.complete(Unit)
                releaseJournal.await()
            }
            true
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-a"))
        // The consumer is now parked inside journal(evt-a) and cannot reach evt-b before the pause.
        withTimeout(5_000) { enteredJournal.await() }
        coordinator.offerCaptured(captured("evt-b"))
        coordinator.setPaused(true)
        releaseJournal.complete(Unit)

        awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 1L }
        h.journaled shouldBe listOf("evt-a")
        coordinator.status.value.acceptedCount shouldBe 1L
    }

    test("pausing rotates the generation so nothing offered afterwards is queued at all") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        coordinator.setPaused(true)

        coordinator.offerCaptured(captured("evt-while-paused"))

        stillHolds { h.journaled shouldBe emptyList() }
        coordinator.status.value.queueDepth shouldBe 0
        coordinator.status.value.droppedAfterRevoke shouldBe 0L
    }

    test("resuming while bound starts a fresh generation and a fresh capture session") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        val firstGeneration = coordinator.status.value.activeGeneration.shouldNotBeNull()
        coVerify(timeout = 5_000) { h.health.startSession(firstGeneration, any(), any()) }
        h.awaitConnected()

        coordinator.setPaused(true)
        coordinator.status.value.activeGeneration shouldBe null
        coordinator.status.value.pausedByUser shouldBe true
        coordinator.status.value.listenerState shouldBe ListenerState.PAUSED
        coVerify(timeout = 5_000) { h.health.endSession(SESSION_ID, any(), "PAUSED") }

        coordinator.setPaused(false)
        val secondGeneration = coordinator.status.value.activeGeneration.shouldNotBeNull()
        secondGeneration shouldNotBe firstGeneration
        coordinator.status.value.listenerState shouldBe ListenerState.CONNECTED
        coVerify(timeout = 5_000) { h.health.startSession(secondGeneration, any(), any()) }

        // A new event is accepted under the resumed generation.
        coordinator.offerCaptured(captured("evt-after-resume"))
        awaitUntil { h.journaled shouldBe listOf("evt-after-resume") }
    }

    test("resuming while unbound does not start a generation or a session") {
        val h = Harness()
        val coordinator = h.coordinator()
        // No onConnected: the listener was never bound, so there is nothing to resume into.
        coordinator.setPaused(true)
        coordinator.setPaused(false)

        coordinator.status.value.activeGeneration shouldBe null
        coordinator.status.value.listenerState shouldBe ListenerState.GRANTED_DISCONNECTED
        coordinator.offerCaptured(captured("evt-unbound"))
        stillHolds { h.journaled shouldBe emptyList() }
        coVerify(exactly = 0) { h.health.startSession(any(), any(), any()) }
    }

    test("cold start: the source list is loaded in the pipeline and drops what is not a source") {
        val h = Harness()
        // observeSources never emits, so sourcesLoaded stays false and nothing is filtered at
        // offer time; process() has to load the list itself and decide.
        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-unlisted", pkg = UNLISTED_PKG))
        coordinator.offerCaptured(captured("evt-enabled", pkg = ENABLED_PKG))

        // One consumer drains the queue in order, so seeing evt-enabled proves evt-unlisted was
        // already decided; it never reached the journal.
        awaitUntil { h.journaled shouldBe listOf("evt-enabled") }
        awaitUntil { coordinator.status.value.acceptedCount shouldBe 1L }
        coVerify(atLeast = 1) { h.sources.sources() }
    }

    test("cold start: a disabled source in the loaded list is dropped too") {
        val h = Harness()
        coEvery { h.sources.sources() } returns listOf(
            sourceConfig(UNLISTED_PKG, enabled = false),
            sourceConfig(ENABLED_PKG),
        )
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-disabled", pkg = UNLISTED_PKG))
        coordinator.offerCaptured(captured("evt-enabled", pkg = ENABLED_PKG))

        awaitUntil { h.journaled shouldBe listOf("evt-enabled") }
    }

    test("once the source list is known an unlisted package is dropped before it is queued") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        // One round trip through the pipeline: after it, the source list is loaded either way.
        coordinator.offerCaptured(captured("probe", pkg = ENABLED_PKG))
        awaitUntil { h.journaled shouldBe listOf("probe") }

        coordinator.offerCaptured(captured("evt-unlisted", pkg = UNLISTED_PKG))

        stillHolds { h.journaled shouldBe listOf("probe") }
        // Dropped at offer time, so it never entered the queue and was never counted as revoked.
        coordinator.status.value.droppedAfterRevoke shouldBe 0L
    }

    test("an own-package event is only accepted when it is marked synthetic") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-own-live", pkg = APP_PKG, origin = CaptureOrigin.LIVE))
        stillHolds { h.journaled shouldBe emptyList() }

        coordinator.offerCaptured(captured("evt-own-synthetic", pkg = APP_PKG, origin = CaptureOrigin.SYNTHETIC))
        awaitUntil { h.journaled shouldBe listOf("evt-own-synthetic") }
    }

    test("an ordinary pipeline failure after acceptance is marked retryable and the consumer keeps going") {
        val h = Harness()
        // Accepted (journaled), then the commit path fails: the row exists and is retried later.
        coEvery { h.ingest.markJournal(any(), any(), any()) } coAnswers {
            if (firstArg<String>() == "evt-boom") throw IllegalStateException("boom")
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-boom"))
        coordinator.offerCaptured(captured("evt-ok"))

        awaitUntil { h.journaled shouldBe listOf("evt-boom", "evt-ok") }
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournalRetryable("evt-boom", "IllegalStateException") }
        awaitUntil { coordinator.status.value.acceptedCount shouldBe 2L }
    }

    test("a CancellationException is propagated, never recorded as a retryable failure") {
        val h = Harness()
        h.journalAnswers { throw CancellationException("cancelled") }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-cancelled"))
        awaitUntil { h.journaled shouldBe listOf("evt-cancelled") }

        // The consumer rethrew instead of looping, so a later event is never picked up and the
        // failure was never swallowed into the journal's retry bookkeeping.
        coordinator.offerCaptured(captured("evt-after-cancel"))
        stillHolds { h.journaled shouldBe listOf("evt-cancelled") }
        coVerify(exactly = 0) { h.ingest.markJournalRetryable(any(), any()) }
    }

    test("disconnecting clears the generation, ends the session and opens a gap") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        coordinator.onDisconnected()

        coordinator.status.value.activeGeneration shouldBe null
        coordinator.status.value.listenerState shouldBe ListenerState.RECONNECTING
        coVerify(timeout = 5_000) { h.health.endSession(SESSION_ID, any(), "DISCONNECTED") }
        coVerify(timeout = 5_000) { h.health.openGap(any(), any(), any(), any()) }
        coVerify(timeout = 5_000) { h.listenerAccess.requestRebind() }

        coordinator.offerCaptured(captured("evt-after-disconnect"))
        stillHolds { h.journaled shouldBe emptyList() }
    }

    // ---- QI-SEC-001: policy changes are ordered against the pipeline lock -------------------

    test("a source disabled while an event waits for the pipeline lock is never journaled") {
        val h = Harness()
        val enteredJournal = CompletableDeferred<Unit>()
        val releaseJournal = CompletableDeferred<Unit>()
        h.journalAnswers { snapshot ->
            if (snapshot.eventId == "evt-a") {
                enteredJournal.complete(Unit)
                releaseJournal.await()
            }
            true
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        // One round trip so the source list is known and evt-b passes the first (cheap) fence.
        coordinator.offerCaptured(captured("probe"))
        awaitUntil { h.journaled shouldBe listOf("probe") }

        coordinator.offerCaptured(captured("evt-a"))
        withTimeout(5_000) { enteredJournal.await() }
        // evt-b passed the pre-lock fence: the source is still enabled at this point.
        coordinator.offerCaptured(captured("evt-b"))
        // The policy change queues on the pipeline lock behind evt-a and ahead of evt-b (the
        // mutex is fair). UNDISPATCHED runs it synchronously up to its first suspension — the
        // lock wait — so it is enqueued before the gate is released, with no timing assumption.
        val change = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.setSourceEnabled(ENABLED_PKG, enabled = false) }
        coVerify(exactly = 0) { h.sources.setEnabled(any(), any()) }
        releaseJournal.complete(Unit)
        change.join()

        awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 1L }
        stillHolds { h.journaled shouldBe listOf("probe", "evt-a") }
        coVerify(exactly = 1) { h.ingest.discardPendingJournal(ENABLED_PKG) }
        // And nothing new for that source is even queued any more.
        coordinator.offerCaptured(captured("evt-c"))
        stillHolds { h.journaled shouldBe listOf("probe", "evt-a") }
        coordinator.status.value.droppedAfterRevoke shouldBe 1L
    }

    test("a pause between acceptance and commit leaves the event pending instead of committing it") {
        val h = Harness()
        val enteredCheckpoint = CompletableDeferred<Unit>()
        val releaseCheckpoint = CompletableDeferred<Unit>()
        coEvery { h.ingest.checkpoint(any()) } coAnswers {
            enteredCheckpoint.complete(Unit)
            releaseCheckpoint.await()
            null
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        // A snapshot with content: the parser yields a candidate, so the pipeline reaches the commit.
        coordinator.offerCaptured(CapturedNotification(Fixtures.snapshot(Fixtures.base(title = "Alice", text = "hello"), packageName = ENABLED_PKG, eventId = "evt-commit"), null))
        withTimeout(5_000) { enteredCheckpoint.await() }
        coordinator.setPaused(true)
        releaseCheckpoint.complete(Unit)

        awaitUntil { h.journaled shouldBe listOf("evt-commit") }
        stillHolds { coVerify(exactly = 0) { h.ingest.commit(any(), any(), any(), any(), any(), any(), any()) } }
        // Not discarded either: it waits in the journal for the resume.
        coVerify(exactly = 0) { h.ingest.markJournal("evt-commit", "DISCARDED", any()) }
    }

    test("replay is held while paused and runs on resume") {
        val h = Harness()
        coEvery { h.ingest.pendingJournal(any(), any()) } returns emptyList()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()
        coordinator.setPaused(true)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        stillHolds { coVerify(exactly = 0) { h.ingest.pendingJournal(any(), any()) } }

        coordinator.setPaused(false)
        coVerify(timeout = 5_000, atLeast = 1) { h.ingest.pendingJournal(any(), any()) }
    }

    test("replay discards a pending row whose source was disabled since and commits the others") {
        val h = Harness()
        val disabled = Fixtures.snapshot(Fixtures.base(title = null, text = null), packageName = UNLISTED_PKG, eventId = "evt-disabled")
        val enabled = Fixtures.snapshot(Fixtures.base(title = null, text = null), packageName = ENABLED_PKG, eventId = "evt-enabled")
        var served = false
        coEvery { h.ingest.pendingJournal(any(), any()) } coAnswers {
            if (served) emptyList() else { served = true; listOf("gen-old" to disabled, "gen-old" to enabled) }
        }
        coEvery { h.ingest.isJournalPending(any()) } returns true
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))

        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournal("evt-disabled", "DISCARDED", "SOURCE_DISABLED") }
        // The enabled one went through the parser (no content → SKIPPED), i.e. it was processed, not discarded.
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournal("evt-enabled", "SKIPPED", any()) }
        coVerify(exactly = 0) { h.ingest.markJournal("evt-enabled", "DISCARDED", any()) }
        // Replay never re-journals.
        h.journaled shouldBe emptyList()
    }

    // ---- QI-SEC-003: a maintenance run is a complete barrier ---------------------------------

    test("a maintenance run drops what was queued, records an exact gap and starts a fresh generation") {
        val h = Harness()
        val enteredJournal = CompletableDeferred<Unit>()
        val releaseJournal = CompletableDeferred<Unit>()
        h.journalAnswers { snapshot ->
            if (snapshot.eventId == "evt-a") {
                enteredJournal.complete(Unit)
                releaseJournal.await()
            }
            true
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()
        val before = coordinator.status.value.activeGeneration.shouldNotBeNull()

        coordinator.offerCaptured(captured("evt-a"))
        withTimeout(5_000) { enteredJournal.await() }
        coordinator.offerCaptured(captured("evt-b"))
        val ran = CompletableDeferred<Unit>()
        val exclusive = launch { h.maintenance.exclusive { ran.complete(Unit) } }
        // Maintenance is announced before it gets the lock: nothing new is queued from here on.
        awaitUntil { coordinator.status.value.activeGeneration shouldBe null }
        coordinator.offerCaptured(captured("evt-during"))
        releaseJournal.complete(Unit)
        withTimeout(5_000) { ran.await() }
        exclusive.join()

        awaitUntil { coordinator.status.value.activeGeneration.shouldNotBeNull() shouldNotBe before }
        coordinator.status.value.listenerState shouldBe ListenerState.CONNECTED
        awaitUntil { coordinator.status.value.droppedAfterRevoke shouldBe 1L }
        stillHolds { h.journaled shouldBe listOf("evt-a") }
        coVerify(timeout = 5_000) { h.health.endSession(SESSION_ID, any(), "MAINTENANCE") }
        coVerify(timeout = 5_000) { h.health.recordGap(any(), any(), GapReason.MAINTENANCE, GapPrecision.EXACT, any()) }
        // Capture works again under the new generation.
        coordinator.offerCaptured(captured("evt-after"))
        awaitUntil { h.journaled shouldBe listOf("evt-a", "evt-after") }
    }

    test("every maintenance run records its own gap, even two in a row and an instant one") {
        val h = Harness()
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)
        h.awaitConnected()

        h.maintenance.exclusive { }
        h.maintenance.exclusive { }

        coVerify(timeout = 5_000, exactly = 2) { h.health.recordGap(any(), any(), GapReason.MAINTENANCE, GapPrecision.EXACT, any()) }
        awaitUntil { coordinator.status.value.activeGeneration.shouldNotBeNull() }
        coordinator.offerCaptured(captured("evt-after-two"))
        awaitUntil { h.journaled shouldBe listOf("evt-after-two") }
    }

    // ---- QI-CAPTURE-013: nothing is read from a notification before the policy is known ---------

    /** A framework notification object: only `packageName` is ever touched before the policy is known. */
    fun sbnOf(pkg: String): StatusBarNotification = mockk(relaxed = true) { every { packageName } returns pkg }

    test("before the source list is known a notification is held unread; once known, only sources are snapshotted") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val created = Collections.synchronizedList(ArrayList<String>())
        every { factory.create(any(), any(), any(), any()) } answers {
            val pkg = firstArg<StatusBarNotification>().packageName
            created += pkg
            captured("evt-$pkg", pkg = pkg)
        }
        // The vault takes a moment: sources.sources() suspends until the test releases it.
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(UNLISTED_PKG))
        coordinator.onPosted(sbnOf(ENABLED_PKG))
        // Held, not materialised: the factory has not seen either of them.
        stillHolds { created shouldBe emptyList() }
        coordinator.status.value.queueDepth shouldBe 0

        vaultOpen.complete(Unit)
        awaitUntil { h.journaled shouldBe listOf("evt-$ENABLED_PKG") }
        // The unlisted one was dropped without ever being read.
        stillHolds { created shouldBe listOf(ENABLED_PKG) }
    }

    test("a held buffer that overflowed before the policy was known records the drop as a gap and keeps only sources") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        every { factory.create(any(), any(), any(), any()) } answers {
            val sbn = firstArg<StatusBarNotification>()
            captured("evt-${sbn.id}", pkg = sbn.packageName)
        }
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        // 300 notifications while the vault is still opening: more than the buffer holds.
        for (i in 1..300) {
            val pkg = if (i % 2 == 0) ENABLED_PKG else UNLISTED_PKG
            coordinator.onPosted(mockk(relaxed = true) { every { packageName } returns pkg; every { id } returns i })
        }
        vaultOpen.complete(Unit)

        // The drop is not hidden: one bounded COLD_START gap for the overflowed batch...
        coVerify(timeout = 5_000, atLeast = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        // ...and what survived is only the enabled source's notifications.
        awaitUntil { h.journaled.size shouldBe 128 }
        h.journaled.all { it.startsWith("evt-") && it.removePrefix("evt-").toInt() % 2 == 0 } shouldBe true
        stillHolds { h.journaled.size shouldBe 128 }
    }

    test("a journal insert that throws is recorded as a gap, not marked retryable on a row that does not exist") {
        val h = Harness()
        h.journalAnswers { snapshot -> if (snapshot.eventId == "evt-busy") throw IllegalStateException("database is locked") else true }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-busy"))
        coordinator.offerCaptured(captured("evt-ok"))

        awaitUntil { h.journaled shouldBe listOf("evt-busy", "evt-ok") }
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.UNKNOWN, GapPrecision.EXACT, any()) }
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.diagnostic("JOURNAL_FAILED", "IllegalStateException", ENABLED_PKG, any()) }
        coVerify(exactly = 0) { h.ingest.markJournalRetryable("evt-busy", any()) }
    }

    test("when the vault does not open, held notifications are dropped unread and a bounded gap is recorded") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        coEvery { h.sources.sources() } throws VaultUnavailableException(KeyFailure.Unavailable("test"))
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))

        coVerify(timeout = 5_000, exactly = 1) { h.health.openGap(any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        stillHolds { h.journaled shouldBe emptyList() }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }

        // A second notification while still locked extends the same gap instead of adding another row.
        coordinator.onPosted(sbnOf(ENABLED_PKG))
        stillHolds { coVerify(exactly = 1) { h.health.openGap(any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } }

        // Once the vault opens and the policy loads, the gap is closed.
        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.closeOpenGaps(any(), GapReason.COLD_START) }
    }

    test("a cold-start loss while the vault is locked is written as a bounded gap once the vault opens") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val locked = VaultUnavailableException(KeyFailure.Unavailable("locked"))
        coEvery { h.sources.sources() } throws locked
        // The gap table is behind the same lock: opening a gap fails too.
        coEvery { h.health.openGap(any(), GapReason.COLD_START, any(), any()) } throws locked
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        coVerify(timeout = 5_000, atLeast = 1) { h.health.openGap(any(), GapReason.COLD_START, any(), any()) }
        stillHolds { coVerify(exactly = 0) { h.health.recordGap(any(), any(), GapReason.COLD_START, any(), any()) } }

        // The vault opens: the loss is written now, bounded, exactly once.
        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    test("a lock-out gap the pipeline could not open is written as a bounded gap once the vault opens") {
        val h = Harness()
        val locked = VaultUnavailableException(KeyFailure.Unavailable("locked"))
        h.journalAnswers { throw locked }
        coEvery { h.health.openGap(any(), GapReason.UNKNOWN, any(), any()) } throws locked
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-locked"))
        awaitUntil { h.journaled shouldBe listOf("evt-locked") }
        awaitUntil { coordinator.status.value.vaultLocked shouldBe true }

        h.vaultState.value = VaultState.Ready(mockk(relaxed = true))
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.UNKNOWN, GapPrecision.BOUNDED, any()) }
        coVerify(timeout = 5_000, exactly = 1) { h.health.closeOpenGaps(any(), GapReason.UNKNOWN) }
    }

    test("a cold-start loss whose settle failed is kept and written on the next policy load") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val locked = VaultUnavailableException(KeyFailure.Unavailable("locked"))
        coEvery { h.sources.sources() } throws locked
        coEvery { h.health.openGap(any(), GapReason.COLD_START, any(), any()) } throws locked
        // The first settle fails half-way (the vault locked again); the second one succeeds.
        coEvery { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } throws locked andThen Unit
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        coVerify(timeout = 5_000, atLeast = 1) { h.health.openGap(any(), GapReason.COLD_START, any(), any()) }

        coEvery { h.sources.sources() } returns listOf(sourceConfig(ENABLED_PKG))
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        // Not forgotten: the next policy load writes it, and only then does it stop being retried.
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG), sourceConfig(UNLISTED_PKG)))
        coVerify(timeout = 5_000, exactly = 2) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        stillHolds { coVerify(exactly = 2) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) } }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    test("a cold-start gap row that lands after the policy loaded is closed at once, not left open") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val policyReady = CompletableDeferred<Unit>()
        val gapGate = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { policyReady.await(); listOf(sourceConfig(ENABLED_PKG)) }
        // The open-gap insert waits for the vault like any other write.
        coEvery { h.health.openGap(any(), GapReason.COLD_START, any(), any()) } coAnswers { gapGate.await(); 7L }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory; it.coldStartTimeoutMs = 200L }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        // The vault did not open in time: the buffer is dropped and the gap row is being opened.
        coVerify(timeout = 5_000, exactly = 1) { h.health.openGap(any(), GapReason.COLD_START, any(), any()) }

        // The vault opens and the policy loads while that insert is still waiting: its settle finds no row.
        policyReady.complete(Unit)
        h.observedSources.emit(listOf(sourceConfig(ENABLED_PKG)))
        coVerify(timeout = 5_000, exactly = 1) { h.health.closeOpenGaps(any(), GapReason.COLD_START) }

        // The insert lands afterwards: closed right away instead of staying open until the next policy load.
        gapGate.complete(Unit)
        coVerify(timeout = 5_000, exactly = 2) { h.health.closeOpenGaps(any(), GapReason.COLD_START) }
        coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) }
    }

    test("a notification held across a disconnect is recorded as a gap, not dropped silently") {
        val h = Harness()
        val factory: SnapshotFactory = mockk()
        val vaultOpen = CompletableDeferred<Unit>()
        coEvery { h.sources.sources() } coAnswers { vaultOpen.await(); listOf(sourceConfig(ENABLED_PKG)) }
        val coordinator = h.coordinator().also { it.snapshotFactory = factory }
        coordinator.onConnected(h.service)

        coordinator.onPosted(sbnOf(ENABLED_PKG))
        // The listener is rebound while the notification is still held: a new generation.
        coordinator.onDisconnected()
        coordinator.onConnected(h.service)
        vaultOpen.complete(Unit)

        // Its arrival predates the disconnect gap, so the held window gets a gap of its own.
        coVerify(timeout = 5_000, exactly = 1) { h.health.recordGap(any(), any(), GapReason.COLD_START, GapPrecision.BOUNDED, any()) }
        stillHolds { coVerify(exactly = 0) { factory.create(any(), any(), any(), any()) } }
    }

    // ---- QI-MEDIA-006: a bitmap stays counted until the copier is done with it -------------------

    test("bitmaps in flight at the copier still count against the queue bound") {
        val h = Harness()
        val bitmap: android.graphics.Bitmap = mockk(relaxed = true)
        coEvery { h.ingest.commit(any(), any(), any(), any(), any(), any(), any()) } returns CommitOutcome(1L, listOf(1L), emptyList(), listOf(1L), 0, false)
        val copying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bitmaps = Collections.synchronizedList(ArrayList<android.graphics.Bitmap?>())
        // Every copy stays in flight until released: an in-flight bitmap is what the bound counts.
        coEvery { h.mediaCopier.copyPending(any(), any()) } coAnswers {
            bitmaps += secondArg<android.graphics.Bitmap?>()
            if (bitmaps.size == 1) copying.complete(Unit)
            release.await()
        }
        fun withBitmap(id: String) = CapturedNotification(Fixtures.snapshot(Fixtures.base(title = "A", text = "picture"), packageName = ENABLED_PKG, eventId = id), bitmap)
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(withBitmap("evt-0"))
        withTimeout(5_000) { copying.await() }
        // The first copy is still running: its bitmap is still counted. Seven more fill the bound
        // (counted at the door, whether queued or already in flight)...
        for (i in 1..7) coordinator.offerCaptured(withBitmap("evt-$i"))
        // ...so the ninth bitmap is dropped at the door (a placeholder is kept), not queued.
        coordinator.offerCaptured(withBitmap("evt-8"))
        release.complete(Unit)

        awaitUntil { bitmaps.size shouldBe 9 }
        bitmaps.take(8).all { it != null } shouldBe true
        bitmaps[8] shouldBe null
    }
})
