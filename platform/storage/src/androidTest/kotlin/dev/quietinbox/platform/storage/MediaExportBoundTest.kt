package dev.quietinbox.platform.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.IngestRepository
import io.kotest.matchers.collections.shouldBeEmpty
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
 * Round-14 finding: the export bounds its media pages to the highest blob id read inside the row
 * transaction (`MediaDao.maxId`), so a picture committed after the snapshot is never exported
 * without its message. The empty-table case (`COALESCE(MAX(id), 0)`) ends the loop on the first page.
 */
@RunWith(AndroidJUnit4::class)
class MediaExportBoundTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private val parser = StandardParser()
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()

    @Before
    fun setUp() {
        wipe()
        holder = DatabaseHolder(context, KeyMaterial(context))
        ingest = IngestRepository(holder)
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

    private fun blob(messageId: Long, name: String, now: Long) = MediaBlobEntity(
        messageId = messageId, fileName = name, thumbFileName = null, mimeType = "image/png", byteCount = 1,
        width = null, height = null, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = now,
    )

    @Test
    fun mediaExportPagesAreBoundedToTheSnapshotsHighestBlobId() = runBlocking {
        ready()
        val db = holder.db()
        val now = 1_700_000_000_000L
        val media = db.mediaDao()

        // Empty table: the bound is 0 and the first page is already empty.
        media.maxId() shouldBe 0L
        media.exportPage(0L, media.maxId(), 10, now).shouldBeEmpty()

        val snapshot = Fixtures.snapshot(Fixtures.bigText("Alice", "picture", tag = "p1"), packageName = KnownSources.TELEGRAM, eventId = "p1", observedAt = now)
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val out = ingest.commit(snapshot, batch, identity.resolve(snapshot, batch), reconciler.reconcile(snapshot.notificationKey, batch.messages, null, lookupById = { null }, postedAtEpochMs = snapshot.postedAtEpochMs), "gen", null, mediaAllowed = false)
        val messageId = out.newMessageIds.single()

        val first = media.insert(blob(messageId, "a", now))
        val bound = media.maxId()
        bound shouldBe first
        // Committed after the snapshot: outside the bound, never in a page.
        val second = media.insert(blob(messageId, "b", now + 1))
        media.maxId() shouldBe second

        media.exportPage(0L, bound, 10, now).map { it.id } shouldBe listOf(first)
        media.exportPage(first, bound, 10, now).shouldBeEmpty()
        media.exportPage(0L, second, 10, now).map { it.id } shouldBe listOf(first, second)
        // JUnit 4 needs a void method: runBlocking must not return the last matcher's value.
        Unit
    }
}
