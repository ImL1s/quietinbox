package dev.quietinbox.platform.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.identity.IdentityResolver
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.reconcile.Reconciler
import dev.quietinbox.core.testing.Fixtures
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.CommitOutcome
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
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
 * Export → wipe → import on a real SQLCipher vault (QI-BACKUP-016): the backup holds exactly what
 * the user could see (no expired copy), reports media it could not include, and a restore rebuilds
 * the conversation projection and brings the media file back under the current key.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var mediaDir: MediaDirectory
    private lateinit var cipher: BlobCipher
    private lateinit var service: BackupService
    private lateinit var maintenance: VaultMaintenance
    private val parser = StandardParser()
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()

    @Before
    fun setUp() {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        ingest = IngestRepository(holder)
        mediaDir = MediaDirectory(context)
        cipher = BlobCipher(keys)
        maintenance = VaultMaintenance()
        service = BackupService(context, holder, keys, cipher, mediaDir, SettingsRepository(context), maintenance)
    }

    @After
    fun tearDown() = runBlocking {
        holder.closeAndDeleteFiles()
        wipe()
    }

    private fun wipe() {
        File(context.filesDir, "keys").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
    }

    private suspend fun ready() = withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }

    private suspend fun commit(snapshot: NotificationSnapshot, retentionMs: Long? = null): CommitOutcome {
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, ingest.checkpoint(id.streamKey), lookupById = { null })
        return ingest.commit(snapshot, batch, id, r, "gen", retentionMs, mediaAllowed = false)
    }

    @Test
    fun exportHoldsOnlyVisibleCopiesReportsSkippedMediaAndRestoreRebuildsTheProjection() = runBlocking {
        ready()
        val db = holder.db()
        val live = commit(Fixtures.snapshot(Fixtures.bigText("Alice", "keep me", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "b1", observedAt = 1_700_000_000_000L))
        val conversationId = live.conversationId!!
        val liveId = live.newMessageIds.single()
        // Expired long ago (1 ms retention on a 2023 timestamp): must not enter the backup.
        commit(Fixtures.snapshot(Fixtures.bigText("Bob", "already expired", tag = "t1"), packageName = KnownSources.TELEGRAM, eventId = "b2", observedAt = 1_700_000_001_000L), retentionMs = 1)
        // One readable blob on the live message, one blob whose file is missing on a second live message.
        cipher.encryptToFile("picture".toByteArray(), mediaDir.file("blob-ok")).shouldBeInstanceOf<KeyResult.Ok<Unit>>()
        val okBlob = db.mediaDao().insert(MediaBlobEntity(messageId = liveId, fileName = "blob-ok", thumbFileName = null, mimeType = "image/png", byteCount = 7, width = 1, height = 1, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = 1L))
        db.messageDao().setMedia(liveId, MediaState.LOCAL_COPY.name, okBlob)
        val other = commit(Fixtures.snapshot(Fixtures.bigText("Carol", "lost picture", tag = "t2"), packageName = KnownSources.TELEGRAM, eventId = "b3", observedAt = 1_700_000_002_000L))
        val otherId = other.newMessageIds.single()
        val lostBlob = db.mediaDao().insert(MediaBlobEntity(messageId = otherId, fileName = "blob-missing", thumbFileName = null, mimeType = "image/png", byteCount = 7, width = 1, height = 1, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = 1L))
        db.messageDao().setMedia(otherId, MediaState.LOCAL_COPY.name, lostBlob)

        val target = File(context.cacheDir, "roundtrip.qibk")
        val exported = service.export(Uri.fromFile(target), "test").shouldBeInstanceOf<BackupResult.Ok>()
        exported.counts.messages shouldBe 2
        exported.counts.media shouldBe 1
        exported.skippedMedia shouldBe 1
        val recoveryKey = service.recoveryKeyText().shouldBeInstanceOf<KeyResult.Ok<String>>().value

        // Wipe the vault (keys stay: the recovery key is what opens the backup) and restore.
        holder.closeAndDeleteFiles() shouldBe true
        mediaDir.deleteAll() shouldBe true
        holder.retry()
        ready()
        val restored = service.import(Uri.fromFile(target), recoveryKey).shouldBeInstanceOf<BackupResult.Ok>()
        restored.counts.messages shouldBe 2
        restored.counts.media shouldBe 1

        val db2 = holder.db()
        val conversations = db2.conversationDao().exportPage(0L, 10)
        conversations.size shouldBe 2
        val first = conversations.first { it.identityKey == db2.conversationDao().get(conversationId)?.identityKey || it.lastMessagePreview == "keep me" }
        first.messageCount shouldBe 1
        first.lastMessagePreview shouldBe "keep me"
        first.lastSenderName shouldBe "Alice"
        val restoredLive = db2.messageDao().exportPage(0L, 10, System.currentTimeMillis()).first { it.body == "keep me" }
        restoredLive.mediaState shouldBe MediaState.LOCAL_COPY.name
        val blob = db2.mediaDao().get(restoredLive.mediaBlobId!!)!!
        BlobCipher(KeyMaterial(context)).decryptFile(mediaDir.file(blob.fileName)).shouldBeInstanceOf<KeyResult.Ok<ByteArray>>().value.decodeToString() shouldBe "picture"
        val restoredOther = db2.messageDao().exportPage(0L, 10, System.currentTimeMillis()).first { it.body == "lost picture" }
        restoredOther.mediaState shouldBe MediaState.FAILED.name
        target.delete()
        Unit
    }

    /** The gate is real: an export during a reset is refused, never half-written. */
    @Test
    fun exportIsRefusedWhileAnExclusiveMaintenanceRunIsActive() = runBlocking {
        ready()
        val target = File(context.cacheDir, "refused.qibk")
        val result = maintenance.exclusive { service.export(Uri.fromFile(target), "test") }
        result shouldBe BackupResult.Failed(BackupResult.Reason.MAINTENANCE)
        target.exists() shouldBe false
        Unit
    }
}
