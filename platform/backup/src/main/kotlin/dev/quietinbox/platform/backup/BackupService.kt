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
import dev.quietinbox.platform.storage.db.SearchTokenEntity
import dev.quietinbox.platform.storage.db.SourceConfigurationEntity
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.core.model.SearchNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackupResult {
    data class Ok(val counts: Counts) : BackupResult
    data class Failed(val reason: Reason, val detail: String? = null) : BackupResult

    enum class Reason { NO_RECOVERY_KEY, KEY_UNAVAILABLE, IO, BAD_HEADER, WRONG_KEY_OR_TAMPERED, TRUNCATED, COUNT_MISMATCH, TOO_LARGE, UNSUPPORTED_VERSION, VAULT_UNAVAILABLE }
}

/**
 * Encrypted export / import through SAF (plan section 11). Export is a logically consistent
 * snapshot serialised as a stream; import stages everything, verifies EOF, counts and every
 * authentication tag, and only then applies in one transaction. Wrong key, truncation and
 * tampering leave the existing vault untouched.
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val keyMaterial: KeyMaterial,
    private val blobCipher: BlobCipher,
    private val mediaDir: MediaDirectory,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    /** The recovery key as text the user must save; created on first call. */
    fun recoveryKeyText(): KeyResult<String> = when (val r = keyMaterial.recovery.getOrCreate()) {
        is KeyResult.Failed -> r
        is KeyResult.Ok -> KeyResult.Ok(RecoveryKeyCodec.encode(r.value)).also { r.value.fill(0) }
    }

    suspend fun export(target: Uri, appVersion: String): BackupResult = withContext(Dispatchers.IO) {
        val key = when (val r = keyMaterial.recovery.getOrCreate()) {
            is KeyResult.Failed -> return@withContext BackupResult.Failed(BackupResult.Reason.KEY_UNAVAILABLE)
            is KeyResult.Ok -> r.value
        }
        val db = try {
            holder.db()
        } catch (e: Exception) {
            return@withContext BackupResult.Failed(BackupResult.Reason.VAULT_UNAVAILABLE)
        }
        try {
            val salt = ByteArray(BackupCrypto.SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val header = BackupCrypto.header(salt)
            val saead = BackupCrypto.streamingAead(key, salt)
            val out: OutputStream = context.contentResolver.openOutputStream(target, "wt")
                ?: return@withContext BackupResult.Failed(BackupResult.Reason.IO, "open")
            out.use { raw ->
                raw.write(header)
                saead.newEncryptingStream(raw, header).use { enc ->
                    val counts = writeRecords(db, enc.bufferedWriter(Charsets.UTF_8), appVersion)
                    return@withContext BackupResult.Ok(counts)
                }
            }
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(target, null, null) }
            BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName)
        } finally {
            key.fill(0)
        }
    }

    private suspend fun writeRecords(db: QuietInboxDatabase, w: BufferedWriter, appVersion: String): Counts {
        fun line(r: BackupRecord) {
            w.write(json.encodeToString(BackupRecord.serializer(), r))
            w.write("\n")
        }
        // Snapshot inside one read transaction so counts and rows agree.
        val sources = db.sourceDao().all()
        val conversations = db.conversationDao().allForExport()
        val messages = db.messageDao().allForExport()
        val revisions = db.revisionDao().allForExport()
        val media = db.mediaDao().allForExport()
        val expected = Counts(sources.size, conversations.size, messages.size, revisions.size, media.size)
        line(BackupRecord.Manifest(BackupCrypto.FORMAT_VERSION.toInt(), QuietInboxDatabase.VERSION, appVersion, System.currentTimeMillis(), expected))
        for (s in sources) line(BackupRecord.Source(s.packageName, s.displayName, s.enabled, s.paused, s.retentionDays, s.mediaEnabled, s.addedAtEpochMs, s.adapterId))
        for (c in conversations) line(BackupRecord.Conversation(c.id, c.packageName, c.profileKey, c.accountKey, c.identityKey, c.identityConfidence, c.title, c.isGroup, c.pinned, c.archived, c.createdAtEpochMs, c.lastActivityEpochMs, c.lastViewedEpochMs))
        for (m in messages) line(
            BackupRecord.Message(
                m.id, m.conversationId, m.sourceMessageId, m.senderName, m.senderKey, m.isSelf, m.body, m.kind, m.sourceTimestampEpochMs, m.timestampQuality,
                m.observedAtEpochMs, m.postedAtEpochMs, m.origin, m.contentStatus, m.dedupState, m.revisionCount, m.observationCount, m.mediaState, m.mediaBlobId,
                m.mediaMimeType, m.fingerprint, m.sortKey, m.expiresAtEpochMs,
            ),
        )
        for (r in revisions) line(BackupRecord.Revision(r.messageId, r.body, r.observedAtEpochMs))
        var mediaWritten = 0
        for (b in media) {
            val bytes = when (val r = blobCipher.decryptFile(mediaDir.file(b.fileName))) {
                is KeyResult.Ok -> r.value
                is KeyResult.Failed -> continue
            }
            if (bytes.size > BackupLimits.MAX_MEDIA_BYTES) continue
            line(BackupRecord.Media(b.id, b.messageId, b.mimeType, b.width, b.height, b.createdAtEpochMs, Base64.encodeToString(bytes, Base64.NO_WRAP)))
            mediaWritten++
        }
        val actual = expected.copy(media = mediaWritten)
        line(BackupRecord.End(actual))
        w.flush()
        return actual
    }

    suspend fun import(source: Uri, recoveryKeyText: String): BackupResult = withContext(Dispatchers.IO) {
        val key = RecoveryKeyCodec.decode(recoveryKeyText) ?: return@withContext BackupResult.Failed(BackupResult.Reason.WRONG_KEY_OR_TAMPERED, "key format")
        val db = try {
            holder.db()
        } catch (e: Exception) {
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
        } catch (e: StagingException) {
            return@withContext BackupResult.Failed(e.reason, e.message)
        } catch (e: Exception) {
            return@withContext BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName)
        } finally {
            key.fill(0)
        }
        apply(db, staged)
    }

    private class StagingException(val reason: BackupResult.Reason, message: String? = null) : Exception(message)

    private class Staged(
        val manifest: BackupRecord.Manifest,
        val sources: List<BackupRecord.Source>,
        val conversations: List<BackupRecord.Conversation>,
        val messages: List<BackupRecord.Message>,
        val revisions: List<BackupRecord.Revision>,
        val media: List<BackupRecord.Media>,
        val end: BackupRecord.End,
    )

    private fun stage(reader: BufferedReader): Staged {
        var manifest: BackupRecord.Manifest? = null
        val sources = ArrayList<BackupRecord.Source>()
        val conversations = ArrayList<BackupRecord.Conversation>()
        val messages = ArrayList<BackupRecord.Message>()
        val revisions = ArrayList<BackupRecord.Revision>()
        val media = ArrayList<BackupRecord.Media>()
        var end: BackupRecord.End? = null
        var records = 0
        var stagedMediaBytes = 0L
        while (true) {
            val line = reader.readLine() ?: break
            if (end != null) throw StagingException(BackupResult.Reason.COUNT_MISMATCH, "data after end")
            if (line.length > BackupLimits.MAX_LINE_BYTES) throw StagingException(BackupResult.Reason.TOO_LARGE, "line")
            if (++records > BackupLimits.MAX_RECORDS) throw StagingException(BackupResult.Reason.TOO_LARGE, "records")
            when (val r = json.decodeFromString(BackupRecord.serializer(), line)) {
                is BackupRecord.Manifest -> {
                    if (manifest != null) throw StagingException(BackupResult.Reason.BAD_HEADER, "duplicate manifest")
                    if (r.formatVersion != BackupCrypto.FORMAT_VERSION.toInt() || r.schemaVersion > QuietInboxDatabase.VERSION) {
                        throw StagingException(BackupResult.Reason.UNSUPPORTED_VERSION, "format ${r.formatVersion} schema ${r.schemaVersion}")
                    }
                    manifest = r
                }
                is BackupRecord.Source -> sources += r
                is BackupRecord.Conversation -> conversations += r
                is BackupRecord.Message -> messages += r
                is BackupRecord.Revision -> revisions += r
                is BackupRecord.Media -> {
                    stagedMediaBytes += r.dataBase64.length * 3L / 4
                    if (stagedMediaBytes > BackupLimits.MAX_STAGED_MEDIA_BYTES) throw StagingException(BackupResult.Reason.TOO_LARGE, "media")
                    media += r
                }
                is BackupRecord.End -> end = r
            }
            if (manifest == null) throw StagingException(BackupResult.Reason.BAD_HEADER, "manifest must come first")
        }
        val m = manifest ?: throw StagingException(BackupResult.Reason.TRUNCATED, "no manifest")
        val e = end ?: throw StagingException(BackupResult.Reason.TRUNCATED, "no end record")
        val actual = Counts(sources.size, conversations.size, messages.size, revisions.size, media.size)
        if (actual != e.actual) throw StagingException(BackupResult.Reason.COUNT_MISMATCH, "end counts")
        if (m.expected.copy(media = actual.media) != actual) throw StagingException(BackupResult.Reason.COUNT_MISMATCH, "manifest counts")
        return Staged(m, sources, conversations, messages, revisions, media, e)
    }

    /** Merge-restore: existing conversations are matched by scope + identity; duplicate fingerprints are skipped. */
    private suspend fun apply(db: QuietInboxDatabase, s: Staged): BackupResult {
        val writtenFiles = ArrayList<String>()
        return try {
            val counts = db.withTransaction {
                for (src in s.sources) {
                    if (db.sourceDao().get(src.packageName) == null) {
                        db.sourceDao().upsert(SourceConfigurationEntity(src.packageName, src.displayName, src.enabled, src.paused, src.retentionDays, src.mediaEnabled, src.addedAtEpochMs, src.adapterId))
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
                var inserted = 0
                for (m in s.messages) {
                    val cid = convMap[m.conversationId] ?: continue
                    val existingFingerprints = db.messageDao().forConversation(cid).map { it.fingerprint }.toHashSet()
                    if (m.fingerprint in existingFingerprints) continue
                    var mediaState = m.mediaState
                    var blobId: Long? = null
                    val media = mediaByOldMessage[m.id]
                    if (media != null) {
                        val bytes = runCatching { Base64.decode(media.dataBase64, Base64.NO_WRAP) }.getOrNull()
                        if (bytes != null && bytes.size <= BackupLimits.MAX_MEDIA_BYTES) {
                            val name = UUID.randomUUID().toString().replace("-", "")
                            if (blobCipher.encryptToFile(bytes, mediaDir.file(name)) is KeyResult.Ok) {
                                writtenFiles += name
                                blobId = db.mediaDao().insert(MediaBlobEntity(messageId = null, fileName = name, thumbFileName = null, mimeType = media.mimeType, byteCount = bytes.size.toLong(), width = media.width, height = media.height, state = MediaState.LOCAL_COPY.name, failureReason = null, createdAtEpochMs = media.createdAtEpochMs))
                                mediaState = MediaState.LOCAL_COPY.name
                            }
                        } else {
                            mediaState = MediaState.FAILED.name
                        }
                    } else if (m.mediaState == MediaState.LOCAL_COPY.name) {
                        mediaState = MediaState.FAILED.name
                    }
                    val newId = db.messageDao().insert(
                        MessageEntity(
                            conversationId = cid, sourceMessageId = m.sourceMessageId, senderName = m.senderName, senderKey = m.senderKey, isSelf = m.isSelf,
                            body = m.body, kind = m.kind, sourceTimestampEpochMs = m.sourceTimestampEpochMs, timestampQuality = m.timestampQuality,
                            observedAtEpochMs = m.observedAtEpochMs, postedAtEpochMs = m.postedAtEpochMs, origin = m.origin, contentStatus = m.contentStatus,
                            dedupState = m.dedupState, revisionCount = m.revisionCount, observationCount = m.observationCount, mediaState = mediaState,
                            mediaBlobId = blobId, mediaUri = null, mediaMimeType = m.mediaMimeType, fingerprint = m.fingerprint, eventId = "restore:${s.manifest.createdAtEpochMs}",
                            sortKey = m.sortKey, expiresAtEpochMs = m.expiresAtEpochMs,
                        ),
                    )
                    msgMap[m.id] = newId
                    inserted++
                    val tokens = SearchNormalizer.tokens(SearchNormalizer.normalize(m.body))
                    if (tokens.isNotEmpty()) db.searchDao().insertTokens(tokens.map { SearchTokenEntity(it, newId) })
                }
                for (r in s.revisions) {
                    val mid = msgMap[r.messageId] ?: continue
                    db.revisionDao().insert(MessageRevisionEntity(messageId = mid, body = r.body, observedAtEpochMs = r.observedAtEpochMs, eventId = "restore"))
                }
                // Recompute counters for touched conversations.
                for (cid in convMap.values.distinct()) {
                    val rows = db.messageDao().forConversation(cid)
                    val c = db.conversationDao().get(cid) ?: continue
                    val last = rows.maxByOrNull { it.sortKey }
                    db.conversationDao().update(
                        c.copy(
                            messageCount = rows.count { it.dedupState != "AMBIGUOUS_REPEAT" },
                            ambiguousCount = rows.count { it.dedupState == "AMBIGUOUS_REPEAT" },
                            lastActivityEpochMs = maxOf(c.lastActivityEpochMs, last?.observedAtEpochMs ?: 0L),
                            lastMessagePreview = last?.body?.take(200) ?: c.lastMessagePreview,
                            lastSenderName = last?.senderName ?: c.lastSenderName,
                        ),
                    )
                }
                Counts(s.sources.size, convMap.size, inserted, s.revisions.size, writtenFiles.size)
            }
            BackupResult.Ok(counts)
        } catch (e: Exception) {
            for (f in writtenFiles) mediaDir.delete(f)
            BackupResult.Failed(BackupResult.Reason.IO, "apply:${e::class.java.simpleName}")
        }
    }
}
