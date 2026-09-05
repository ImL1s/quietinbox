package dev.quietinbox.platform.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.ListenerState
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.storage.db.VaultState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

        fun coordinator() = CaptureCoordinator(context, ingest, sources, health, settings, vault, mediaCopier, listenerAccess)
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

    test("an ordinary pipeline failure is marked retryable and the consumer keeps going") {
        val h = Harness()
        h.journalAnswers { snapshot ->
            if (snapshot.eventId == "evt-boom") throw IllegalStateException("boom")
            true
        }
        val coordinator = h.coordinator()
        coordinator.onConnected(h.service)

        coordinator.offerCaptured(captured("evt-boom"))
        coordinator.offerCaptured(captured("evt-ok"))

        awaitUntil { h.journaled shouldBe listOf("evt-boom", "evt-ok") }
        coVerify(timeout = 5_000, exactly = 1) { h.ingest.markJournalRetryable("evt-boom", "IllegalStateException") }
        awaitUntil { coordinator.status.value.acceptedCount shouldBe 1L }
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
})
