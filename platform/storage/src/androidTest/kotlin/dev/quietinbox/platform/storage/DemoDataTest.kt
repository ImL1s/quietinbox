package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.DemoDataRepository
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SearchRepository
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The demo seeder against real SQLCipher (the native library cannot run on the JVM, so this is an
 * instrumented test like `VaultRoundTripTest`): seed → assert the rows every screen needs exist →
 * clear → assert nothing demo-tagged is left.
 */
@RunWith(AndroidJUnit4::class)
class DemoDataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var holder: DatabaseHolder
    private lateinit var demo: DemoDataRepository
    private lateinit var inbox: InboxRepository
    private lateinit var search: SearchRepository

    private val packages = DemoDataRepository.PACKAGE_LIKE
    private val generations = DemoDataRepository.GENERATION_LIKE

    @Before
    fun setUp() {
        wipe()
        holder = DatabaseHolder(context, KeyMaterial(context))
        demo = DemoDataRepository(holder)
        inbox = InboxRepository(holder)
        search = SearchRepository(holder)
    }

    @After
    fun tearDown() = runBlocking {
        holder.closeAndDeleteFiles()
        wipe()
    }

    private fun wipe() {
        File(context.filesDir, "keys").deleteRecursively()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
        }
    }

    private suspend fun ready() = withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }

    @Test
    fun seedsEveryScreenThenClearsCompletely() = runBlocking {
        ready()
        val dao = holder.db().demoDao()
        val now = System.currentTimeMillis()

        // A non-demo row written before seeding must survive clear(): the seeder only touches its own tags.
        holder.db().sourceDao().upsert(
            dev.quietinbox.platform.storage.db.SourceConfigurationEntity(
                packageName = "com.example.real", displayName = "Real source", enabled = true, paused = false,
                retentionDays = null, mediaEnabled = false, addedAtEpochMs = now, adapterId = null,
            ),
        )

        val counts = demo.seed(now)
        counts.conversations shouldBe 8
        counts.messages shouldBeGreaterThanOrEqualTo 100

        // Inbox and conversation screens.
        dao.countSources(packages) shouldBe 3
        dao.countConversations(packages) shouldBeGreaterThanOrEqualTo 8
        dao.countMessages(packages) shouldBeGreaterThanOrEqualTo 100

        // Every honesty label the UI can show has at least one row behind it.
        dao.countMessagesWithDedupState(packages, DedupState.AMBIGUOUS_REPEAT.name) shouldBeGreaterThanOrEqualTo 1
        dao.countObservationLinks(packages, DedupState.AMBIGUOUS_REPEAT.name) shouldBeGreaterThanOrEqualTo 1
        dao.countRevisions(packages) shouldBeGreaterThanOrEqualTo 1
        dao.countSearchTokens(packages) shouldBeGreaterThanOrEqualTo 100

        // Capture page: sessions, possible gaps and local diagnostics are all non-empty.
        dao.countSessions(generations) shouldBe 1
        dao.countAllGaps() shouldBe 2
        dao.countDiagnostics(packages) shouldBeGreaterThanOrEqualTo 2

        // Search runs over the same index the capture pipeline writes.
        search.search(DemoDataRepository.SEARCH_SAMPLE) shouldHaveAtLeastSize 1
        search.search("擷取這串字不存在") shouldBe emptyList()

        // Seeding twice must not double anything.
        val messagesAfterFirst = dao.countMessages(packages)
        demo.seed(now)
        dao.countConversations(packages) shouldBe 8
        dao.countMessages(packages) shouldBe messagesAfterFirst
        dao.countSessions(generations) shouldBe 1
        dao.countAllGaps() shouldBe 2

        demo.clear()
        holder.db().sourceDao().get("com.example.real") shouldNotBe null

        dao.countSources(packages) shouldBe 0
        dao.countConversations(packages) shouldBe 0
        dao.countMessages(packages) shouldBe 0
        dao.countRevisions(packages) shouldBe 0
        dao.countObservationLinks(packages, DedupState.AMBIGUOUS_REPEAT.name) shouldBe 0
        dao.countSearchTokens(packages) shouldBe 0
        dao.countSessions(generations) shouldBe 0
        dao.countAllGaps() shouldBe 0
        dao.countDiagnostics(packages) shouldBe 0
        search.search(DemoDataRepository.SEARCH_SAMPLE) shouldBe emptyList()
        inbox.observeConversations(archived = false, packages = emptySet()).first() shouldBe emptyList()
        inbox.observeConversations(archived = true, packages = emptySet()).first() shouldBe emptyList()

        Unit
    }

    @Test
    fun seededDataIsShapedTheWayTheReadPathsExpect() = runBlocking {
        ready()
        val now = System.currentTimeMillis()
        demo.seed(now)

        val active = inbox.observeConversations(archived = false, packages = emptySet()).first()
        val archived = inbox.observeConversations(archived = true, packages = emptySet()).first()
        active shouldHaveAtLeastSize 7
        archived shouldHaveAtLeastSize 1
        // The inbox orders pinned first; the seed pins exactly one conversation.
        active.first().pinned shouldBe true
        active.count { it.pinned } shouldBe 1

        for (conversation in active + archived) {
            conversation.title!!.isNotBlank() shouldBe true
            conversation.lastMessagePreview!!.isNotBlank() shouldBe true
            val messages = inbox.observeMessages(conversation.id).first()
            // The projection has to agree with the rows, exactly as commit keeps it.
            conversation.messageCount shouldBe messages.count { it.dedupState != DedupState.AMBIGUOUS_REPEAT }
            conversation.ambiguousCount shouldBe messages.count { it.dedupState == DedupState.AMBIGUOUS_REPEAT }
            conversation.lastActivityEpochMs shouldBe messages.maxOf { it.sortKey }
            // Analytics reads sortKey and asks for the last 30 days; nothing may fall outside it.
            messages.all { it.sortKey in (now - 30L * 24 * 60 * 60 * 1000)..now } shouldBe true
            messages.all { it.observedAtEpochMs >= it.sortKey } shouldBe true
        }

        Unit
    }
}
