package dev.quietinbox.platform.storage.retention

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.VaultUnavailableException
import dev.quietinbox.platform.storage.settings.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTL clean-up (plan section 8): expired messages with their revisions, links, index tokens and
 * media files; journal rows past their TTL; suppression tokens; old diagnostics and gaps.
 * WorkManager gives no millisecond guarantee, so the UI filters expired content separately.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val retention: RetentionService,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        retention.runOnce(System.currentTimeMillis())
        Result.success()
    } catch (e: VaultUnavailableException) {
        Result.retry()
    } catch (e: Exception) {
        Result.failure()
    }
}

@Singleton
class RetentionService @Inject constructor(
    private val holder: DatabaseHolder,
    private val settings: SettingsRepository,
    private val mediaDir: MediaDirectory,
) {
    suspend fun runOnce(now: Long): RetentionReport {
        val db = holder.db()
        val s = settings.current()
        var deletedMessages = 0
        while (true) {
            val ids = db.messageDao().expiredIds(now, 500)
            if (ids.isEmpty()) break
            db.messageDao().delete(ids)
            deletedMessages += ids.size
        }
        val orphans = db.mediaDao().orphans()
        for (blob in orphans) {
            mediaDir.delete(blob.fileName)
            blob.thumbFileName?.let { mediaDir.delete(it) }
        }
        if (orphans.isNotEmpty()) db.mediaDao().delete(orphans.map { it.id })
        val journal = db.journalDao().deleteExpired(now)
        val suppression = db.suppressionDao().deleteExpired(now)
        val diagCutoff = now - 30L * DAY_MS
        val diagnostics = db.diagnosticsDao().deleteBefore(diagCutoff)
        val gapCutoff = now - s.retentionDays.toLong() * DAY_MS
        db.healthDao().deleteGapsBefore(gapCutoff)
        db.healthDao().deleteSessionsBefore(gapCutoff)
        db.healthDao().deleteSummariesBefore(gapCutoff)
        db.checkpointDao().deleteStale(now - 14L * DAY_MS)
        val emptyConversations = db.conversationDao().emptyOlderThan(now - 7L * DAY_MS)
        for (id in emptyConversations) db.conversationDao().delete(id)
        return RetentionReport(deletedMessages, orphans.size, journal, suppression, diagnostics, emptyConversations.size)
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000
        const val WORK_NAME = "quietinbox.retention"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

data class RetentionReport(
    val deletedMessages: Int,
    val deletedMedia: Int,
    val deletedJournal: Int,
    val deletedSuppressions: Int,
    val deletedDiagnostics: Int,
    val deletedEmptyConversations: Int,
)

/** Location of encrypted media blobs; file names are opaque ids only. */
@Singleton
class MediaDirectory @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) {
    val dir: File = File(context.filesDir, "media")

    fun file(name: String): File = File(dir, name)

    fun delete(name: String) {
        runCatching { File(dir, name).delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
