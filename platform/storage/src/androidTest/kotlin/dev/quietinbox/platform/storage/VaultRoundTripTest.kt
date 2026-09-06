package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SearchRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * Real SQLCipher on a real device/emulator (the native library cannot run on the JVM):
 * key wrap → vault open → journal → commit → query → search → sliding-window dedup → reopen.
 */
@RunWith(AndroidJUnit4::class)
class VaultRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var inbox: InboxRepository
    private lateinit var search: SearchRepository

    @Before
    fun setUp() {
        wipe()
        val keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        ingest = IngestRepository(holder)
        inbox = InboxRepository(holder, MediaDirectory(context))
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
    fun keyWrapOpenCommitQuerySearch() = runBlocking {
        ready()
        val parser = StandardParser()
        val identity = IdentityResolver()
        val reconciler = Reconciler()

        // Window 1: [A, B]. No retention: the fixture timestamps are in 2023 and expired copies are hidden at read time.
        val s1 = Fixtures.snapshot(
            Fixtures.messaging(conversationTitle = "Vault Test", isGroup = true, shortcutId = "sc-1") {
                message("Alice", "明天開會 A", 1_000)
                message("Bob", "hello B", 2_000)
            },
            packageName = KnownSources.LINE,
            eventId = "e1",
            notificationKey = "k1",
        )
        ingest.journal(s1, "gen", 60_000) shouldBe true
        val b1 = parser.parse(s1)
        val id1 = identity.resolve(s1, b1)
        val r1 = reconciler.reconcile(s1.notificationKey, b1.messages, ingest.checkpoint(id1.streamKey), lookupById = { null })
        val out1 = ingest.commit(s1, b1, id1, r1, "gen", null, mediaAllowed = true)
        out1.newMessageIds shouldHaveSize 2

        // Window 2: [A, B, C] on the same notification key → only C is new.
        val s2 = Fixtures.snapshot(
            Fixtures.messaging(conversationTitle = "Vault Test", isGroup = true, shortcutId = "sc-1") {
                message("Alice", "明天開會 A", 1_000)
                message("Bob", "hello B", 2_000)
                message("Alice", "third C", 3_000)
            },
            packageName = KnownSources.LINE,
            eventId = "e2",
            notificationKey = "k1",
        )
        ingest.journal(s2, "gen", 60_000) shouldBe true
        val b2 = parser.parse(s2)
        val id2 = identity.resolve(s2, b2)
        val r2 = reconciler.reconcile(s2.notificationKey, b2.messages, ingest.checkpoint(id2.streamKey), lookupById = { null })
        val out2 = ingest.commit(s2, b2, id2, r2, "gen", null, mediaAllowed = true)
        out2.newMessageIds shouldHaveSize 1
        out2.conversationId shouldBe out1.conversationId

        val conversations = inbox.observeConversations(archived = false, packages = emptySet()).first()
        conversations shouldHaveSize 1
        conversations[0].messageCount shouldBe 3
        conversations[0].title shouldBe "Vault Test"

        val messages = inbox.observeMessages(out1.conversationId!!).first()
        messages.map { it.body } shouldBe listOf("明天開會 A", "hello B", "third C")
        messages.all { it.dedupState == DedupState.CANDIDATE } shouldBe true

        // CJK substring and Latin fragment search, both parameterised.
        search.search("開會").map { it.message.body } shouldBe listOf("明天開會 A")
        search.search("hel").map { it.message.body } shouldBe listOf("hello B")
        search.search("nothing-here") shouldHaveSize 0

        // Deleting suppresses replay of the same fingerprint.
        inbox.deleteMessages(listOf(messages[2].id), s2.observedAtEpochMs, 86_400_000)
        val r3 = reconciler.reconcile(s2.notificationKey, b2.messages, null, lookupById = { null })
        val out3 = ingest.commit(s2.copy(eventId = "e3"), b2, id2, r3, "gen", null, mediaAllowed = true)
        out3.suppressedCount shouldBe 1
        // A and B already exist: with no checkpoint they are matched by fingerprint, not duplicated.
        out3.newMessageIds shouldHaveSize 0
        inbox.observeMessages(out1.conversationId!!).first().map { it.body } shouldBe listOf("明天開會 A", "hello B")

        Unit
    }

    @Test
    fun vaultReopensWithPersistedKey() = runBlocking {
        ready()
        val s = Fixtures.snapshot(Fixtures.bigText("Alice", "persist me", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "p1")
        val parser = StandardParser()
        val b = parser.parse(s)
        val id = IdentityResolver().resolve(s, b)
        val r = Reconciler().reconcile(s.notificationKey, b.messages, null, lookupById = { null })
        ingest.journal(s, "gen", 60_000)
        ingest.commit(s, b, id, r, "gen", null, mediaAllowed = false)

        // Simulate a process restart: a fresh holder reads the wrapped key from disk.
        (holder.state.value as VaultState.Ready).db.close()
        val again = DatabaseHolder(context, KeyMaterial(context))
        withTimeout(20_000) { again.state.filterIsInstance<VaultState.Ready>().first() }
        val rows = InboxRepository(again, MediaDirectory(context)).observeConversations(false, emptySet()).first()
        rows shouldHaveSize 1
        rows[0].lastMessagePreview shouldBe "persist me"
        again.state.value.shouldBeInstanceOf<VaultState.Ready>()
        (again.state.value as VaultState.Ready).db.close()
    }

    /** Whole-repo review I1: a deleted conversation must not come back as an empty row on replay. */
    @Test
    fun deletedConversationDoesNotResurrectOnReplay() = runBlocking {
        ready()
        val reconciler = Reconciler()
        val s = Fixtures.snapshot(Fixtures.bigText("Alice", "delete me later", tag = "d1"), packageName = KnownSources.TELEGRAM, eventId = "d1")
        val b = StandardParser().parse(s)
        val id = IdentityResolver().resolve(s, b)
        val r = reconciler.reconcile(s.notificationKey, b.messages, null, lookupById = { null })
        val out = ingest.commit(s, b, id, r, "gen", null, mediaAllowed = false)
        val conversationId = out.conversationId!!
        inbox.deleteConversation(conversationId, System.currentTimeMillis(), 30L * 86_400_000)
        ingest.findConversationId(id) shouldBe null

        // Active-notification replay of the same content: every item is suppressed, so nothing may be stored.
        val replay = reconciler.reconcile(s.notificationKey, b.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        val again = ingest.commit(s.copy(eventId = "d2"), b, id, replay, "gen", null, mediaAllowed = false)
        again.conversationId shouldBe null
        again.newMessageIds shouldBe emptyList()
        ingest.findConversationId(id) shouldBe null
        holder.db().conversationDao().observeCount().first() shouldBe 0
        Unit
    }
}
