package dev.quietinbox.platform.storage

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
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.AnalyticsRepository
import dev.quietinbox.platform.storage.repo.CommitOutcome
import dev.quietinbox.platform.storage.repo.IngestRepository
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.ResetResult
import dev.quietinbox.platform.storage.repo.SearchRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.retention.RetentionService
import dev.quietinbox.platform.storage.settings.SettingsRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * What "delete" and "expire" must mean on a real SQLCipher vault (QI-DATA-004, QI-DATA-007,
 * QI-SEC-003): journal text does not outlive its commit, the conversation projection is rebuilt
 * from what remains, expired copies are hidden before retention runs, removing a source removes
 * everything it owned, and "delete everything" is verified and leaves no old key behind.
 */
@RunWith(AndroidJUnit4::class)
class DeletionGraphTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keys: KeyMaterial
    private lateinit var holder: DatabaseHolder
    private lateinit var ingest: IngestRepository
    private lateinit var inbox: InboxRepository
    private lateinit var sources: SourceRepository
    private lateinit var search: SearchRepository
    private lateinit var mediaDir: MediaDirectory
    private lateinit var settings: SettingsRepository
    private lateinit var maintenance: VaultMaintenance

    private val parser = StandardParser()
    private val identity = IdentityResolver()
    private val reconciler = Reconciler()

    @Before
    fun setUp() {
        wipe()
        keys = KeyMaterial(context)
        holder = DatabaseHolder(context, keys)
        mediaDir = MediaDirectory(context)
        settings = SettingsRepository(context)
        maintenance = VaultMaintenance()
        ingest = IngestRepository(holder)
        inbox = InboxRepository(holder, mediaDir)
        sources = SourceRepository(holder, mediaDir)
        search = SearchRepository(holder)
    }

    @After
    fun tearDown() = runBlocking {
        holder.closeAndDeleteFiles()
        wipe()
    }

    private fun wipe() {
        File(context.filesDir, "keys").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            File(context.getDatabasePath("quietinbox.vault").path + suffix).delete()
        }
    }

    private suspend fun ready() = withTimeout(20_000) { holder.state.filterIsInstance<VaultState.Ready>().first() }

    /** Journals and commits one BigText notification; [retentionMs] null keeps the copy forever. */
    private suspend fun commit(snapshot: NotificationSnapshot, retentionMs: Long? = null): CommitOutcome {
        ingest.journal(snapshot, "gen", 60_000) shouldBe true
        val batch = parser.parse(snapshot)
        val id = identity.resolve(snapshot, batch)
        val previous = ingest.checkpoint(id.streamKey)
        val r = reconciler.reconcile(snapshot.notificationKey, batch.messages, previous, lookupById = { null })
        return ingest.commit(snapshot, batch, id, r, "gen", retentionMs, mediaAllowed = false)
    }

    private fun bigText(sender: String, body: String, eventId: String, tag: String, pkg: String = KnownSources.TELEGRAM, observedAt: Long = 1_700_000_000_000L) =
        Fixtures.snapshot(Fixtures.bigText(sender, body, tag = tag), packageName = pkg, eventId = eventId, observedAt = observedAt)

    @Test
    fun journalPayloadIsClearedTheMomentTheRowLeavesPending() = runBlocking {
        ready()
        val db = holder.db()
        commit(bigText("Alice", "committed text", "j1", "t1"))
        db.journalDao().state("j1") shouldBe "COMMITTED"
        db.journalDao().payload("j1") shouldBe ""

        val skipped = bigText("Alice", "skipped text", "j2", "t2")
        ingest.journal(skipped, "gen", 60_000) shouldBe true
        db.journalDao().payload("j2")!!.contains("skipped text") shouldBe true
        ingest.markJournal("j2", "SKIPPED", "NO_CONTENT")
        db.journalDao().payload("j2") shouldBe ""

        // A retry keeps the payload: the event is not finished yet.
        val retried = bigText("Alice", "retry text", "j3", "t3")
        ingest.journal(retried, "gen", 60_000) shouldBe true
        ingest.markJournalRetryable("j3", "boom")
        db.journalDao().state("j3") shouldBe "PENDING"
        db.journalDao().payload("j3")!!.contains("retry text") shouldBe true
        Unit
    }

    @Test
    fun deletingTheNewestMessageRebuildsTheConversationProjection() = runBlocking {
        ready()
        val first = commit(bigText("Alice", "first", "p1", "t1", observedAt = 1_700_000_000_000L))
        val conversationId = first.conversationId!!
        commit(bigText("Bob", "second", "p2", "t2", observedAt = 1_700_000_001_000L))
        // Same sender+tag stream would not create a second conversation; a different tag does.
        // Use the first conversation for the projection check.
        val db = holder.db()
        val before = db.conversationDao().get(conversationId)!!
        before.messageCount shouldBe 1
        before.lastMessagePreview shouldBe "first"

        val newer = commit(bigText("Carol", "newer", "p3", "t1", observedAt = 1_700_000_002_000L))
        newer.conversationId shouldBe conversationId
        val grown = db.conversationDao().get(conversationId)!!
        grown.messageCount shouldBe 2
        grown.lastMessagePreview shouldBe "newer"
        grown.lastSenderName shouldBe "Carol"

        inbox.deleteMessages(newer.newMessageIds, System.currentTimeMillis(), 86_400_000)
        val rebuilt = db.conversationDao().get(conversationId)!!
        rebuilt.messageCount shouldBe 1
        rebuilt.lastMessagePreview shouldBe "first"
        rebuilt.lastSenderName shouldBe "Alice"
        rebuilt.lastActivityEpochMs shouldBe 1_700_000_000_000L
        Unit
    }

    @Test
    fun expiredCopiesAreHiddenBeforeRetentionRunsAndRetentionRebuildsTheProjection() = runBlocking {
        ready()
        val keep = commit(bigText("Alice", "keep me", "x1", "t1", observedAt = 1_700_000_000_000L))
        val conversationId = keep.conversationId!!
        // Observed in the past with a 1 ms retention: expired long before "now".
        val gone = commit(bigText("Bob", "already expired", "x2", "t1", observedAt = 1_700_000_001_000L), retentionMs = 1)
        gone.conversationId shouldBe conversationId
        val db = holder.db()
        db.conversationDao().get(conversationId)!!.messageCount shouldBe 2

        inbox.observeMessages(conversationId).first().map { it.body } shouldBe listOf("keep me")
        search.search("expired") shouldHaveSize 0
        search.search("keep") shouldHaveSize 1
        inbox.observeCounts().first().messages shouldBe 1
        AnalyticsRepository(holder).messagesBetween(0, Long.MAX_VALUE).map { it.body } shouldBe listOf("keep me")

        val report = RetentionService(holder, settings, mediaDir, maintenance).runOnce(System.currentTimeMillis())!!
        report.deletedMessages shouldBe 1
        val rebuilt = db.conversationDao().get(conversationId)!!
        rebuilt.messageCount shouldBe 1
        rebuilt.lastMessagePreview shouldBe "keep me"
        Unit
    }

    @Test
    fun removingASourceWithItsDataLeavesNothingBehind() = runBlocking {
        ready()
        val db = holder.db()
        val pkg = KnownSources.TELEGRAM
        sources.enable(pkg, "Telegram", null, 1L)
        val out = commit(bigText("Alice", "owned by the source", "r1", "t1", pkg = pkg))
        val messageId = out.newMessageIds.single()
        // A media blob with a real file, linked to the message.
        val blobFile = mediaDir.file("blob-r1")
        blobFile.parentFile!!.mkdirs()
        blobFile.writeBytes(ByteArray(16) { it.toByte() })
        val blobId = db.mediaDao().insert(MediaBlobEntity(messageId = messageId, fileName = "blob-r1", thumbFileName = null, mimeType = "image/png", byteCount = 16, width = 1, height = 1, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = 1L))
        db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, blobId)
        // A deletion token, a summary, a diagnostic and a pending journal row, all owned by the source.
        val scoped = db.conversationDao().get(out.conversationId!!)!!
        db.suppressionDao().upsert(dev.quietinbox.platform.storage.db.DeletionSuppressionEntity("${scoped.packageName}|${scoped.profileKey}#${scoped.identityKey}", "fp", Long.MAX_VALUE))
        db.healthDao().insertSummary(dev.quietinbox.platform.storage.db.SummaryObservationEntity(packageName = pkg, observedAtEpochMs = 1L, messageCount = 3, conversationCount = 1, eventId = "s1"))
        ingest.diagnostic("PARSE_WARNINGS", "x", pkg, 1L)
        val pending = bigText("Alice", "never committed", "r-pending", "t9", pkg = pkg)
        ingest.journal(pending, "gen", 60_000) shouldBe true
        // Another source's data must survive.
        val other = commit(bigText("Zed", "other source", "o1", "t1", pkg = KnownSources.WHATSAPP))

        sources.remove(pkg, deleteData = true)

        db.sourceDao().get(pkg) shouldBe null
        db.conversationDao().get(out.conversationId!!) shouldBe null
        db.messageDao().get(messageId) shouldBe null
        db.mediaDao().get(blobId) shouldBe null
        blobFile.exists() shouldBe false
        db.suppressionDao().isSuppressed("${scoped.packageName}|${scoped.profileKey}#${scoped.identityKey}", "fp", 0L) shouldBe 0
        db.healthDao().summaryCountSince(0L) shouldBe 0
        db.diagnosticsDao().countsSince(0L) shouldHaveSize 0
        db.journalDao().state("r-pending") shouldBe "DISCARDED"
        db.journalDao().payload("r-pending") shouldBe ""
        db.conversationDao().get(other.conversationId!!) shouldNotBe null
        Unit
    }

    @Test
    fun deleteEverythingIsVerifiedAndNoCachedCipherOutlivesTheOldKey() = runBlocking {
        ready()
        commit(bigText("Alice", "to be wiped", "w1", "t1"))
        val cipher = BlobCipher(keys)
        val oldFile = mediaDir.file("old-blob")
        cipher.encryptToFile("old".toByteArray(), oldFile).shouldBeInstanceOf<KeyResult.Ok<Unit>>()
        val oldCiphertext = oldFile.readBytes()
        val vault = VaultRepository(holder, keys, mediaDir, settings, maintenance)

        vault.deleteEverything() shouldBe ResetResult.Done

        holder.state.value.shouldBeInstanceOf<VaultState.Ready>()
        holder.db().messageDao().observeCount(0L).first() shouldBe 0
        mediaDir.dir.listFiles().orEmpty() shouldHaveSize 0
        // The same BlobCipher instance, after the reset, encrypts with the *new* media key: a
        // fresh process (new KeyMaterial, new cipher) can read it...
        val newFile = mediaDir.file("new-blob")
        cipher.encryptToFile("new".toByteArray(), newFile).shouldBeInstanceOf<KeyResult.Ok<Unit>>()
        val freshProcess = BlobCipher(KeyMaterial(context))
        freshProcess.decryptFile(newFile).shouldBeInstanceOf<KeyResult.Ok<ByteArray>>().value.decodeToString() shouldBe "new"
        // ...and the ciphertext made before the reset no longer authenticates under the new key.
        oldFile.writeBytes(oldCiphertext)
        freshProcess.decryptFile(oldFile).shouldBeInstanceOf<KeyResult.Failed>().failure shouldBe KeyFailure.Tampered
        Unit
    }
}
