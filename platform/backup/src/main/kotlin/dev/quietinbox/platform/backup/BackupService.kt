package dev.quietinbox.platform.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyMaterial
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.crypto.RecoveryKeyCodec
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.MessageEntity
import dev.quietinbox.platform.storage.db.MessageRevisionEntity
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.settings.SettingsRepository
import dev.quietinbox.platform.storage.db.SearchTokenEntity
import dev.quietinbox.platform.storage.db.SourceConfigurationEntity
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.core.model.SearchNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackupResult {
    /** [skippedMedia] > 0 means the file is a partial backup: media that could not be read or was too large is not in it. */
    data class Ok(val counts: Counts, val skippedMedia: Int = 0) : BackupResult
    data class Failed(val reason: Reason, val detail: String? = null) : BackupResult

    enum class Reason { NO_RECOVERY_KEY, KEY_UNAVAILABLE, IO, BAD_HEADER, WRONG_KEY_OR_TAMPERED, CORRUPT, TRUNCATED, COUNT_MISMATCH, TOO_LARGE, UNSUPPORTED_VERSION, VAULT_UNAVAILABLE, MAINTENANCE }
}

/**
 * Encrypted export / import through SAF (plan section 11). Export is a logically consistent
 * snapshot serialised as a stream, read in keyset pages inside one read transaction (never the
 * whole vault in memory) and never containing a copy that is already expired; import stages
 * everything, verifies EOF, counts and every authentication tag, and only then applies in one
 * transaction. Wrong key, truncation and tampering leave the existing vault untouched.
 *
 * Both run under the maintenance gate (QI-BACKUP-016): export is cancellable vault work, so a
 * reset can stop it; import is an exclusive run, so capture, media copies, retention and a reset
 * cannot interleave with it (the window is recorded as a capture gap).
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val keyMaterial: KeyMaterial,
    private val blobCipher: BlobCipher,
    private val mediaDir: MediaDirectory,
    private val settings: SettingsRepository,
    private val maintenance: VaultMaintenance,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    private companion object {
        /** Rows per keyset page while exporting. */
        const val PAGE = 500
    }

    /** The recovery key as text the user must save; created on first call. */
    fun recoveryKeyText(): KeyResult<String> = when (val r = keyMaterial.recovery.getOrCreate()) {
        is KeyResult.Failed -> r
        is KeyResult.Ok -> KeyResult.Ok(RecoveryKeyCodec.encode(r.value)).also { r.value.fill(0) }
    }

    suspend fun export(target: Uri, appVersion: String): BackupResult =
        maintenance.work { exportNow(target, appVersion) } ?: BackupResult.Failed(BackupResult.Reason.MAINTENANCE)

    private suspend fun exportNow(target: Uri, appVersion: String): BackupResult = withContext(Dispatchers.IO) {
        val key = when (val r = keyMaterial.recovery.getOrCreate()) {
            is KeyResult.Failed -> return@withContext BackupResult.Failed(BackupResult.Reason.KEY_UNAVAILABLE)
            is KeyResult.Ok -> r.value
        }
        val db = try {
            holder.db()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext BackupResult.Failed(BackupResult.Reason.VAULT_UNAVAILABLE)
        }
        // The ciphertext is produced into a private temp file first and copied to the user's
        // document only once it is complete, so a failure never damages a pre-existing backup.
        val staging = File(context.cacheDir, "backup-" + UUID.randomUUID().toString().replace("-", "") + ".qibk")
        try {
            val salt = ByteArray(BackupCrypto.SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val header = BackupCrypto.header(salt)
            val saead = BackupCrypto.streamingAead(key, salt)
            val written = FileOutputStream(staging).use { raw ->
                raw.write(header)
                saead.newEncryptingStream(raw, header).use { enc ->
                    writeRecords(db, enc.bufferedWriter(Charsets.UTF_8), appVersion)
                }
            }
            val out: OutputStream = context.contentResolver.openOutputStream(target, "wt")
                ?: return@withContext BackupResult.Failed(BackupResult.Reason.IO, "open")
            out.use { dest -> FileInputStream(staging).use { it.copyTo(dest) } }
            BackupResult.Ok(written.counts, written.skippedMedia)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName)
        } finally {
            key.fill(0)
            staging.delete()
        }
    }

    private class Written(val counts: Counts, val skippedMedia: Int)

    /**
     * Streams every table in keyset pages inside one read transaction, so the counts in the
     * manifest and the rows agree even while capture continues, and no table is ever held in
     * memory as a whole. Expired copies are not exported: a backup holds what the user could see.
     *
     * Only the row reads happen inside the transaction (milliseconds); the media files are
     * decrypted and streamed *after* it, so a large media set never holds the SQLite write lock
     * against live capture (round-11 finding).
     */
    private suspend fun writeRecords(db: QuietInboxDatabase, w: BufferedWriter, appVersion: String): Written {
        fun line(r: BackupRecord) {
            w.write(json.encodeToString(BackupRecord.serializer(), r))
            w.write("\n")
        }
        val now = System.currentTimeMillis()
        val mediaRows = ArrayList<MediaBlobEntity>()
        val expected = db.withTransaction {
            val sources = db.sourceDao().all()
            val expected = Counts(
                sources = sources.size,
                conversations = db.conversationDao().count(),
                messages = db.messageDao().exportCount(now),
                revisions = db.revisionDao().exportCount(now),
                media = db.mediaDao().exportCount(now),
            )
            line(BackupRecord.Manifest(BackupCrypto.FORMAT_VERSION.toInt(), QuietInboxDatabase.VERSION, appVersion, now, expected))
            for (s in sources) line(BackupRecord.Source(s.packageName, s.displayName, s.enabled, s.paused, s.retentionDays, s.mediaEnabled, s.addedAtEpochMs, s.adapterId))
            var after = 0L
            while (true) {
                val page = db.conversationDao().exportPage(after, PAGE)
                if (page.isEmpty()) break
                for (c in page) line(BackupRecord.Conversation(c.id, c.packageName, c.profileKey, c.accountKey, c.identityKey, c.identityConfidence, c.title, c.isGroup, c.pinned, c.archived, c.createdAtEpochMs, c.lastActivityEpochMs, c.lastViewedEpochMs))
                after = page.last().id
            }
            after = 0L
            while (true) {
                val page = db.messageDao().exportPage(after, PAGE, now)
                if (page.isEmpty()) break
                for (m in page) line(
                    BackupRecord.Message(
                        m.id, m.conversationId, m.sourceMessageId, m.senderName, m.senderKey, m.isSelf, m.body, m.kind, m.sourceTimestampEpochMs, m.timestampQuality,
                        m.observedAtEpochMs, m.postedAtEpochMs, m.origin, m.contentStatus, m.dedupState, m.revisionCount, m.observationCount, m.mediaState, m.mediaBlobId,
                        m.mediaMimeType, m.fingerprint, m.sortKey, m.expiresAtEpochMs,
                    ),
                )
                after = page.last().id
            }
            after = 0L
            while (true) {
                val page = db.revisionDao().exportPage(after, PAGE, now)
                if (page.isEmpty()) break
                for (r in page) line(BackupRecord.Revision(r.messageId, r.body, r.observedAtEpochMs))
                after = page.last().id
            }
            // Media rows are metadata only (file name, size, dimensions): small enough to hold.
            after = 0L
            while (true) {
                val page = db.mediaDao().exportPage(after, PAGE, now)
                if (page.isEmpty()) break
                mediaRows += page
                after = page.last().id
            }
            expected
        }
        // Outside the transaction: disk reads and AEAD decryption of every blob.
        var mediaWritten = 0
        var skipped = 0
        for (b in mediaRows) {
            val bytes = when (val r = blobCipher.decryptFile(mediaDir.file(b.fileName))) {
                is KeyResult.Ok -> r.value
                is KeyResult.Failed -> {
                    skipped++
                    continue
                }
            }
            if (bytes.size > BackupLimits.MAX_MEDIA_BYTES) {
                skipped++
                continue
            }
            line(BackupRecord.Media(b.id, b.messageId, b.mimeType, b.width, b.height, b.createdAtEpochMs, Base64.encodeToString(bytes, Base64.NO_WRAP)))
            mediaWritten++
        }
        val actual = expected.copy(media = mediaWritten)
        line(BackupRecord.End(actual, skipped))
        w.flush()
        return Written(actual, skipped)
    }

    /** Exclusive: nothing else writes the vault while a restore is applied. */
    suspend fun import(source: Uri, recoveryKeyText: String): BackupResult = maintenance.exclusive { importNow(source, recoveryKeyText) }

    private suspend fun importNow(source: Uri, recoveryKeyText: String): BackupResult = withContext(Dispatchers.IO) {
        val key = RecoveryKeyCodec.decode(recoveryKeyText) ?: return@withContext BackupResult.Failed(BackupResult.Reason.WRONG_KEY_OR_TAMPERED, "key format")
        val db = try {
            holder.db()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext BackupResult.Failed(BackupResult.Reason.VAULT_UNAVAILABLE)
        }
        val staged = try {
            val input: InputStream = context.contentResolver.openInputStream(source)
                ?: return@withContext BackupResult.Failed(BackupResult.Reason.IO, "open")
            input.use { raw ->
                val header = ByteArray(BackupCrypto.HEADER_BYTES)
                var read = 0
                while (read < header.size) {
                    val n = raw.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                val salt = BackupCrypto.parseHeader(header) ?: return@withContext BackupResult.Failed(BackupResult.Reason.BAD_HEADER)
                val saead = BackupCrypto.streamingAead(key, salt)
                val dec = saead.newDecryptingStream(raw, header)
                stage(dec.bufferedReader(Charsets.UTF_8))
            }
        } catch (e: java.io.IOException) {
            return@withContext BackupResult.Failed(BackupResult.Reason.WRONG_KEY_OR_TAMPERED, e::class.java.simpleName)
        } catch (e: java.security.GeneralSecurityException) {
            return@withContext BackupResult.Failed(BackupResult.Reason.WRONG_KEY_OR_TAMPERED, e::class.java.simpleName)
        } catch (e: kotlinx.serialization.SerializationException) {
            return@withContext BackupResult.Failed(BackupResult.Reason.CORRUPT, "record")
        } catch (e: StagingException) {
            return@withContext BackupResult.Failed(e.reason, e.message)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName)
        } finally {
            key.fill(0)
        }
        apply(db, staged)
    }

    private val stager = BackupStager(json)

    internal fun stage(reader: BufferedReader): Staged = stager.stage(reader)

    /**
     * Merge-restore: existing conversations are matched by scope + identity. Only messages that
     * already existed *before* this import are skipped (same fingerprint + sort key + observed
     * time), so legitimate duplicates inside the backup keep their multiplicity.
     */
    private suspend fun apply(db: QuietInboxDatabase, s: Staged): BackupResult {
        val writtenFiles = ArrayList<String>()
        val usedFiles = HashSet<String>() // blobs referenced by a message that was actually inserted
        var committed = false
        // Blobs are decoded and encrypted to disk BEFORE the write transaction so the SQLite write
        // lock is never held during Tink work and file I/O (live capture would otherwise stall).
        class Prepared(val fileName: String, val byteCount: Long)
        val prepared = HashMap<Long, Prepared?>() // old message id -> encrypted file, or null when it failed
        val now = System.currentTimeMillis()
        val retentionMs = settings.current().retentionDays * 24L * 60L * 60L * 1000L
        return try {
            // Inside the try so a failure or cancellation while encrypting still removes every file.
            for (media in s.media) {
                val oldId = media.messageId ?: continue
                val bytes = runCatching { Base64.decode(media.dataBase64, Base64.NO_WRAP) }.getOrNull()
                if (bytes == null || bytes.size > BackupLimits.MAX_MEDIA_BYTES) {
                    prepared[oldId] = null
                    continue
                }
                val name = UUID.randomUUID().toString().replace("-", "")
                if (blobCipher.encryptToFile(bytes, mediaDir.file(name)) is KeyResult.Ok) {
                    writtenFiles += name
                    prepared[oldId] = Prepared(name, bytes.size.toLong())
                } else {
                    prepared[oldId] = null
                }
            }
            val counts = db.withTransaction {
                for (src in s.sources) {
                    if (db.sourceDao().get(src.packageName) == null) {
                        // Restoring never silently starts capturing: sources come back disabled and are re-enabled in Capture.
                        db.sourceDao().upsert(SourceConfigurationEntity(src.packageName, src.displayName, false, src.paused, src.retentionDays, src.mediaEnabled, src.addedAtEpochMs, src.adapterId))
                    }
                }
                val convMap = HashMap<Long, Long>()
                for (c in s.conversations) {
                    val existing = db.conversationDao().find(c.packageName, c.profileKey, c.accountKey, c.identityKey)
                    convMap[c.id] = existing?.id ?: db.conversationDao().insert(
                        ConversationEntity(
                            packageName = c.packageName, profileKey = c.profileKey, accountKey = c.accountKey, identityKey = c.identityKey,
                            identityConfidence = c.identityConfidence, title = c.title, isGroup = c.isGroup, pinned = c.pinned, archived = c.archived,
                            createdAtEpochMs = c.createdAtEpochMs, lastActivityEpochMs = c.lastActivityEpochMs, lastViewedEpochMs = c.lastViewedEpochMs,
                            messageCount = 0, ambiguousCount = 0, summaryOnlyCount = 0, lastMessagePreview = null, lastSenderName = null,
                        ),
                    )
                }
                val msgMap = HashMap<Long, Long>()
                val mediaByOldMessage = s.media.filter { it.messageId != null }.associateBy { it.messageId!! }
                // Pre-existing content per conversation, computed once (O(n)), before any insert.
                // Counted per key so an existing duplicate consumes exactly one backup copy.
                val preExisting = HashMap<Long, HashMap<String, Int>>()
                for (cid in convMap.values.distinct()) {
                    val counts = HashMap<String, Int>()
                    for (row in db.messageDao().forConversation(cid)) counts.merge("${row.fingerprint}|${row.sortKey}|${row.observedAtEpochMs}", 1, Int::plus)
                    preExisting[cid] = counts
                }
                var inserted = 0
                var skippedOrphans = 0
                for (m in s.messages) {
                    val cid = convMap[m.conversationId]
                    if (cid == null) {
                        skippedOrphans++
                        continue
                    }
                    val dupKey = "${m.fingerprint}|${m.sortKey}|${m.observedAtEpochMs}"
                    val remaining = preExisting.getValue(cid)[dupKey] ?: 0
                    if (remaining > 0) {
                        preExisting.getValue(cid)[dupKey] = remaining - 1
                        continue
                    }
                    // Insert the message first, then the blob bound to its new id (retention treats
                    // messageId == null blobs as orphans and would delete them otherwise).
                    var mediaState = m.mediaState
                    val media = mediaByOldMessage[m.id]
                    val blob = media?.let { prepared[m.id] }
                    if (media != null && blob == null) mediaState = MediaState.FAILED.name
                    if (media == null && m.mediaState == MediaState.LOCAL_COPY.name) mediaState = MediaState.FAILED.name
                    if (blob != null) mediaState = MediaState.PENDING.name
                    val newId = db.messageDao().insert(
                        MessageEntity(
                            conversationId = cid, sourceMessageId = m.sourceMessageId, senderName = m.senderName, senderKey = m.senderKey, isSelf = m.isSelf,
                            body = m.body, kind = m.kind, sourceTimestampEpochMs = m.sourceTimestampEpochMs, timestampQuality = m.timestampQuality,
                            observedAtEpochMs = m.observedAtEpochMs, postedAtEpochMs = m.postedAtEpochMs, origin = m.origin, contentStatus = m.contentStatus,
                            dedupState = m.dedupState, revisionCount = m.revisionCount, observationCount = m.observationCount, mediaState = mediaState,
                            mediaBlobId = null, mediaUri = null, mediaMimeType = m.mediaMimeType, fingerprint = m.fingerprint, eventId = "restore:${s.manifest.createdAtEpochMs}",
                            sortKey = m.sortKey,
                            // A backup older than the retention window must not be swept on the next
                            // retention run; expiry is re-based on the current setting.
                            expiresAtEpochMs = m.expiresAtEpochMs?.let { maxOf(it, now + retentionMs) },
                        ),
                    )
                    if (blob != null) {
                        usedFiles += blob.fileName
                        val blobId = db.mediaDao().insert(MediaBlobEntity(messageId = newId, fileName = blob.fileName, thumbFileName = null, mimeType = media.mimeType, byteCount = blob.byteCount, width = media.width, height = media.height, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = media.createdAtEpochMs))
                        db.messageDao().setMedia(newId, MediaState.LOCAL_COPY.name, blobId)
                    }
                    msgMap[m.id] = newId
                    inserted++
                    val tokens = SearchNormalizer.tokens(SearchNormalizer.normalize(m.body))
                    if (tokens.isNotEmpty()) db.searchDao().insertTokens(tokens.map { SearchTokenEntity(it, newId) })
                }
                var restoredRevisions = 0
                for (r in s.revisions) {
                    val mid = msgMap[r.messageId] ?: continue
                    restoredRevisions++
                    db.revisionDao().insert(MessageRevisionEntity(messageId = mid, body = r.body, observedAtEpochMs = r.observedAtEpochMs, eventId = "restore"))
                }
                // The one projection rebuild every deletion, expiry and restore shares (QI-DATA-004).
                db.conversationDao().rebuildProjection(convMap.values.distinct(), now)
                if (skippedOrphans > 0) {
                    db.diagnosticsDao().insert(dev.quietinbox.platform.storage.db.DiagnosticEventEntity(code = "RESTORE_ORPHAN_MESSAGES", detail = skippedOrphans.toString(), packageName = null, atEpochMs = System.currentTimeMillis()))
                }
                Counts(s.sources.size, convMap.size, inserted, restoredRevisions, usedFiles.size)
            }
            committed = true
            // Blobs prepared for messages that were skipped (duplicates, orphans) have no row: remove them.
            for (f in writtenFiles) if (f !in usedFiles) mediaDir.delete(f)
            BackupResult.Ok(counts)
        } catch (e: Exception) {
            // Before the commit every blob is an orphan; after it (a cancellation landing on the way
            // out) only the ones no inserted message references may go. Runs before the rethrow.
            for (f in writtenFiles) if (!committed || f !in usedFiles) mediaDir.delete(f)
            if (e is CancellationException) throw e
            BackupResult.Failed(BackupResult.Reason.IO, "apply:${e::class.java.simpleName}")
        }
    }
}
