package dev.quietinbox.platform.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.platform.crypto.BlobCipher
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.retention.MediaDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 */
@Singleton
class MediaCopier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val cipher: BlobCipher,
    private val dir: MediaDirectory,
) {
    private val parallelism = Semaphore(2)

    suspend fun copyPending(messageIds: List<Long>, bitmap: Bitmap?) = withContext(Dispatchers.IO) {
        val db = holder.db()
        coroutineScope {
            messageIds.map { id ->
                async {
                    val row = db.messageDao().get(id) ?: return@async
                    if (row.mediaState != MediaState.PENDING.name) return@async
                    parallelism.withPermit {
                        val state = when {
                            row.mediaUri != null -> copyUri(id, Uri.parse(row.mediaUri), row.mediaMimeType)
                            bitmap != null -> copyBitmap(id, bitmap)
                            else -> MediaState.FAILED to null
                        }
                        db.messageDao().setMedia(id, state.first.name, state.second)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun copyUri(messageId: Long, uri: Uri, mimeType: String?): Pair<MediaState, Long?> {
        if (uri.scheme != "content") return MediaState.PLACEHOLDER_ONLY to null
        if (dir.totalBytes() > QUOTA_BYTES) return MediaState.TOO_LARGE to null
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
            return MediaState.PERMISSION_DENIED to null
        } catch (e: FileNotFoundException) {
            return MediaState.URI_EXPIRED to null
        } catch (e: TimeoutCancellationException) {
            return MediaState.FAILED to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MediaState.FAILED to null
        }
        if (bytes == null) return MediaState.TOO_LARGE to null
        if (bytes.isEmpty()) return MediaState.URI_EXPIRED to null
        return store(messageId, bytes, mimeType)
    }

    private suspend fun copyBitmap(messageId: Long, bitmap: Bitmap): Pair<MediaState, Long?> {
        val out = ByteArrayOutputStream()
        val ok = runCatching { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }.getOrDefault(false)
        if (!ok) return MediaState.FAILED to null
        val bytes = out.toByteArray()
        if (bytes.size > MAX_BYTES) return MediaState.TOO_LARGE to null
        return store(messageId, bytes, "image/png")
    }

    private suspend fun store(messageId: Long, bytes: ByteArray, mimeType: String?): Pair<MediaState, Long?> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val isImage = opts.outWidth > 0 && opts.outHeight > 0
        if (!isImage && mimeType?.startsWith("image/") == true) return MediaState.FAILED to null

        val name = UUID.randomUUID().toString().replace("-", "")
        val thumbName = if (isImage) "$name.t" else null
        when (val r = cipher.encryptToFile(bytes, dir.file(name))) {
            is KeyResult.Failed -> return MediaState.FAILED to null
            is KeyResult.Ok -> Unit
        }
        if (thumbName != null) {
            val thumb = thumbnail(bytes, opts.outWidth, opts.outHeight)
            if (thumb != null) cipher.encryptToFile(thumb, dir.file(thumbName))
        }
        val id = holder.db().mediaDao().insert(
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
        return MediaState.LOCAL_COPY to id
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
