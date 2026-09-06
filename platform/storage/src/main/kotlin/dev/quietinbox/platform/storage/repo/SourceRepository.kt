package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.SourceConfigurationEntity
import dev.quietinbox.platform.storage.retention.MediaDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Enabled sources and their per-source policy. */
@Singleton
class SourceRepository @Inject constructor(
    private val holder: DatabaseHolder,
    private val mediaDir: MediaDirectory,
) {
    fun observeSources(): Flow<List<SourceConfiguration>> =
        holder.flowWithDb { db -> db.sourceDao().observeAll() }.map { rows -> rows.map { it.toDomain() } }

    suspend fun sources(): List<SourceConfiguration> = holder.db().sourceDao().all().map { it.toDomain() }

    suspend fun get(packageName: String): SourceConfiguration? = holder.db().sourceDao().get(packageName)?.toDomain()

    suspend fun enable(packageName: String, displayName: String, adapterId: String?, now: Long) {
        val db = holder.db()
        val existing = db.sourceDao().get(packageName)
        db.sourceDao().upsert(
            existing?.copy(enabled = true, displayName = displayName, adapterId = adapterId)
                ?: SourceConfigurationEntity(
                    packageName = packageName,
                    displayName = displayName,
                    enabled = true,
                    paused = false,
                    retentionDays = null,
                    mediaEnabled = true,
                    addedAtEpochMs = now,
                    adapterId = adapterId,
                ),
        )
    }

    suspend fun setEnabled(packageName: String, enabled: Boolean) = holder.db().sourceDao().setEnabled(packageName, enabled)
    suspend fun setPaused(packageName: String, paused: Boolean) = holder.db().sourceDao().setPaused(packageName, paused)
    suspend fun setMediaEnabled(packageName: String, enabled: Boolean) = holder.db().sourceDao().setMediaEnabled(packageName, enabled)

    suspend fun setRetention(packageName: String, days: Int?, defaultDays: Int) {
        val db = holder.db()
        db.withTransaction {
            db.sourceDao().setRetention(packageName, days)
            db.messageDao().recomputeExpiryForPackage(packageName, (days ?: defaultDays) * DAY_MS)
        }
    }

    /**
     * Removes a source. Stopping capture and deleting saved copies are separate user decisions
     * (plan section 2), hence the explicit flag. Either way the source's pending journal rows are
     * discarded: nothing captured for a source that is no longer one may be committed later.
     * With [deleteData] the whole deletion graph goes in one transaction — conversations (which
     * cascade to messages, revisions, links and index tokens), media rows, suppression tokens,
     * summaries and diagnostics — and the media files right after it (QI-DATA-004).
     */
    suspend fun remove(packageName: String, deleteData: Boolean) {
        val db = holder.db()
        val files = db.withTransaction {
            db.sourceDao().delete(packageName)
            db.checkpointDao().deleteForPackage(packageName)
            db.journalDao().discardPending(packageName)
            if (!deleteData) return@withTransaction emptyList()
            val blobs = db.mediaDao().forPackage(packageName)
            if (blobs.isNotEmpty()) db.mediaDao().delete(blobs.map { it.id })
            db.conversationDao().deleteForPackage(packageName)
            db.suppressionDao().deleteForScopePrefix("$packageName|")
            db.healthDao().deleteSummariesForPackage(packageName)
            db.diagnosticsDao().deleteForPackage(packageName)
            blobs.fileNames()
        }
        for (f in files) mediaDir.delete(f)
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000
    }
}
