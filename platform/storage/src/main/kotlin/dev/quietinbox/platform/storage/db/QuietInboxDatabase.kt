package dev.quietinbox.platform.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single encrypted vault. Every table listed in plan section 8 lives here so that one
 * SQLCipher key protects messages, search index, checkpoints and media references together.
 *
 * Migrations must be explicit; `fallbackToDestructiveMigration()` is forbidden by the plan.
 */
@Database(
    entities = [
        SourceConfigurationEntity::class,
        CaptureSessionEntity::class,
        GapIntervalEntity::class,
        EventJournalEntity::class,
        CheckpointEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MessageRevisionEntity::class,
        ObservationLinkEntity::class,
        MediaBlobEntity::class,
        DeletionSuppressionEntity::class,
        SearchTokenEntity::class,
        SummaryObservationEntity::class,
        DiagnosticEventEntity::class,
    ],
    version = QuietInboxDatabase.VERSION,
    exportSchema = true,
)
abstract class QuietInboxDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun journalDao(): JournalDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun revisionDao(): RevisionDao
    abstract fun observationLinkDao(): ObservationLinkDao
    abstract fun mediaDao(): MediaDao
    abstract fun suppressionDao(): SuppressionDao
    abstract fun searchDao(): SearchDao
    abstract fun healthDao(): HealthDao
    abstract fun diagnosticsDao(): DiagnosticsDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "quietinbox.vault"
    }
}
