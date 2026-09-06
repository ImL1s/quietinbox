package dev.quietinbox.platform.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultMaintenance
import dev.quietinbox.platform.storage.retention.MediaDirectory
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, time-limited copy of media referenced by a notification into encrypted blobs
 * (plan section 10). Only `content://` URIs and bitmaps the notification already carried are
 * accepted; nothing is downloaded. Failures keep a specific [MediaState] so the UI can show why.
 *
 * Runs as vault work (QI-SEC-003): refused during a reset or restore, cancelled when one starts.
 * The blob row and the message link are written in one transaction, and every file written for a
 * copy that did not reach that commit is removed on the way out (QI-MEDIA-006).
 */
@Singleton
class MediaCopier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val cipher: BlobCipher,
    private val dir: MediaDirectory,
    private val settings: SettingsRepository,
    private val maintenance: VaultMaintenance,
) {
    private val parallelism = Semaphore(2)

    suspend fun copyPending(messageIds: List<Long>, bitmap: Bitmap?) {
        maintenance.work {
            withContext(Dispatchers.IO) {
                val db = holder.db()
                // Re-read the effective setting at the moment of copying: a switch turned off (or a
                // disclosure never accepted) between commit and copy must win (QI-PRIV-002).
                if (!settings.current().mediaCopyEnabled) {
                    for (id in messageIds) {
                        val row = db.messageDao().get(id) ?: continue
                        if (row.mediaState == MediaState.PENDING.name) db.messageDao().setMedia(id, MediaState.DISABLED_BY_USER.name, null)
                    }
                    return@withContext
                }
                // A Bitmap is not thread-safe: compress it once here, then share the immutable bytes.
                val bitmapBytes: ByteArray? = bitmap?.let { b ->
                    val out = ByteArrayOutputStream()
                    if (runCatching { b.compress(Bitmap.CompressFormat.PNG, 100, out) }.getOrDefault(false)) out.toByteArray() else null
                }
                coroutineScope {
                    messageIds.map { id ->
                        async {
                            parallelism.withPermit {
                                val row = db.messageDao().get(id) ?: return@withPermit
                                if (row.mediaState != MediaState.PENDING.name) return@withPermit
                                val state = when {
                                    row.mediaUri != null -> copyUri(id, Uri.parse(row.mediaUri), row.mediaMimeType)
                                    bitmap != null -> copyBitmapBytes(id, bitmapBytes)
                                    else -> MediaState.FAILED
                                }
                                // LOCAL_COPY was linked inside store()'s transaction; only failures are written here.
                                if (state != MediaState.LOCAL_COPY) db.messageDao().setMedia(id, state.name, null)
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private suspend fun copyUri(messageId: Long, uri: Uri, mimeType: String?): MediaState {
        if (uri.scheme != "content") return MediaState.PLACEHOLDER_ONLY
        if (dir.totalBytes() > QUOTA_BYTES) return MediaState.TOO_LARGE
        val bytes = try {
            withTimeout(READ_TIMEOUT_MS) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@withTimeout null
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }
        } catch (e: SecurityException) {
            return MediaState.PERMISSION_DENIED
        } catch (e: FileNotFoundException) {
            return MediaState.URI_EXPIRED
        } catch (e: TimeoutCancellationException) {
            return MediaState.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MediaState.FAILED
        }
        if (bytes == null) return MediaState.TOO_LARGE
        if (bytes.isEmpty()) return MediaState.URI_EXPIRED
        return store(messageId, bytes, mimeType)
    }

    private suspend fun copyBitmapBytes(messageId: Long, bytes: ByteArray?): MediaState {
        if (bytes == null) return MediaState.FAILED
        if (bytes.size > MAX_BYTES) return MediaState.TOO_LARGE
        return store(messageId, bytes, "image/png")
    }

    /**
     * Writes the blob (and thumbnail), then links row and message in one transaction. Any failure
     * after a file was written removes that file: no orphan survives a cancelled or failed copy.
     */
    private suspend fun store(messageId: Long, bytes: ByteArray, mimeType: String?): MediaState {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val isImage = opts.outWidth > 0 && opts.outHeight > 0
        if (!isImage && mimeType?.startsWith("image/") == true) return MediaState.FAILED

        val name = UUID.randomUUID().toString().replace("-", "")
        val written = ArrayList<String>(2)
        try {
            when (cipher.encryptToFile(bytes, dir.file(name))) {
                is KeyResult.Failed -> return MediaState.FAILED
                is KeyResult.Ok -> written += name
            }
            var thumbName: String? = null
            if (isImage) {
                val candidate = "$name.t"
                val thumb = thumbnail(bytes, opts.outWidth, opts.outHeight)
                // A thumbnail that failed to encrypt is simply absent; its name is never recorded.
                if (thumb != null && cipher.encryptToFile(thumb, dir.file(candidate)) is KeyResult.Ok) {
                    thumbName = candidate
                    written += candidate
                } else {
                    dir.delete(candidate)
                }
            }
            val db = holder.db()
            db.withTransaction {
                val id = db.mediaDao().insert(
                    MediaBlobEntity(
                        messageId = messageId,
                        fileName = name,
                        thumbFileName = thumbName,
                        mimeType = mimeType ?: if (isImage) "image/*" else null,
                        byteCount = bytes.size.toLong(),
                        width = opts.outWidth.takeIf { isImage },
                        height = opts.outHeight.takeIf { isImage },
                        state = MediaState.LOCAL_COPY.name,
                        failureReason = null,
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, id)
            }
            written.clear()
            return MediaState.LOCAL_COPY
        } finally {
            for (f in written) dir.delete(f)
        }
    }

    private fun thumbnail(bytes: ByteArray, w: Int, h: Int): ByteArray? {
        var sample = 1
        while (w / sample > THUMB_MAX || h / sample > THUMB_MAX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Decrypts a blob (or its thumbnail) into memory. No plaintext disk cache exists anywhere. */
    suspend fun load(blobId: Long, thumbnail: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        val blob = holder.db().mediaDao().get(blobId) ?: return@withContext null
        val name = if (thumbnail) blob.thumbFileName ?: blob.fileName else blob.fileName
        when (val r = cipher.decryptFile(dir.file(name))) {
            is KeyResult.Ok -> r.value
            is KeyResult.Failed -> null
        }
    }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val QUOTA_BYTES = 512L * 1024 * 1024
        const val THUMB_MAX = 512
        const val READ_TIMEOUT_MS = 10_000L
    }
}
