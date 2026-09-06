package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.CommitOutcome
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.SearchRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
 * QI-SEARCH-011 and QI-DEDUP-009 on a real vault: a run of false-positive candidates cannot hide
 * a true hit or under-fill a page, and a deletion token does not swallow a later post with the
 * same text.
 */
@RunWith(AndroidJUnit4::class)
class SearchPagingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var inbox: InboxRepository
    private lateinit var search: SearchRepository
    private val parser = StandardParser()
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()

    @Before
    fun setUp() {
        wipe()
        holder = DatabaseHolder(context, KeyMaterial(context))
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
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
    }

    private suspend fun ready() = withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }

    /** Commits one BigText notification with no checkpoint (every item is decided as New). */
    private suspend fun commit(snapshot: NotificationSnapshot): CommitOutcome {
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, null, lookupById = { null }, postedAtEpochMs = snapshot.postedAtEpochMs)
        return ingest.commit(snapshot, batch, id, r, "gen", null, mediaAllowed = false)
    }

    @Test
    fun aRunOfFalsePositiveCandidatesNeitherHidesATrueHitNorUnderFillsThePage() = runBlocking {
        ready()
        // The one true hit is the oldest row.
        commit(Fixtures.snapshot(Fixtures.bigText("Zed", "say hello world today", tag = "true"), packageName = KnownSources.TELEGRAM, eventId = "true", observedAt = 1_700_000_000_000L))
        // 250 newer rows contain every query token ("hel", "ell", "llo", "wor", "orl", "rld") but not the substring.
        for (i in 1..250) {
            commit(Fixtures.snapshot(Fixtures.bigText("Zed", "world hello", tag = "fp-$i"), packageName = KnownSources.TELEGRAM, eventId = "fp-$i", observedAt = 1_700_000_000_000L + i * 1_000L))
        }
        // The old single-page query returned the first 100 candidates (all false positives) and no hit.
        val page = search.searchPage("hello world", limit = 100)
        page.hits.map { it.message.body } shouldBe listOf("say hello world today")
        page.next.shouldBeNull()
        search.search("hello world", limit = 100) shouldHaveSize 1

        // Pages of verified hits stay full while candidates remain, and the cursor resumes without overlap.
        val first = search.searchPage("world hello", limit = 100)
        first.hits shouldHaveSize 100
        val second = search.searchPage("world hello", limit = 100, cursor = first.next)
        second.hits shouldHaveSize 100
        val third = search.searchPage("world hello", limit = 100, cursor = second.next)
        third.hits shouldHaveSize 50
        third.next.shouldBeNull()
        (first.hits + second.hits + third.hits).map { it.message.id }.toSet() shouldHaveSize 250
        // JUnit 4 needs a void method: runBlocking must not return the last matcher's value.
        Unit
    }

    @Test
    fun aDeletionTokenSuppressesAReplayOfTheSamePostButNotALaterPostWithTheSameText() = runBlocking {
        ready()
        val posted = 1_700_000_000_000L
        val original = Fixtures.snapshot(Fixtures.bigText("Alice", "好", tag = "s1"), packageName = KnownSources.TELEGRAM, eventId = "d1", observedAt = posted + 50, postedAt = posted)
        val out = commit(original)
        out.newMessageIds shouldHaveSize 1
        inbox.deleteMessages(out.newMessageIds, posted + 1_000, 30L * 86_400_000)

        // Replay of the same post (checkpoint lost): suppressed.
        val replay = commit(original.copy(eventId = "d2", observedAtEpochMs = posted + 2_000))
        replay.suppressedCount shouldBe 1
        replay.newMessageIds shouldHaveSize 0

        // A later post with the same text from the same chat: a new message, stored.
        val later = Fixtures.snapshot(Fixtures.bigText("Alice", "好", tag = "s1"), packageName = KnownSources.TELEGRAM, eventId = "d3", observedAt = posted + 60_050, postedAt = posted + 60_000)
        val stored = commit(later)
        stored.suppressedCount shouldBe 0
        stored.newMessageIds shouldHaveSize 1
        Unit
    }
}
