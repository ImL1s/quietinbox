package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.SourceConfigurationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Enabled sources and their per-source policy. */
@Singleton
class SourceRepository @Inject constructor(
    private val holder: DatabaseHolder,
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
     * (plan section 2), hence the explicit flag.
     */
    suspend fun remove(packageName: String, deleteData: Boolean) {
        val db = holder.db()
        db.withTransaction {
            db.sourceDao().delete(packageName)
            db.checkpointDao().deleteForPackage(packageName)
            if (deleteData) db.conversationDao().deleteForPackage(packageName)
        }
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000
    }
}
