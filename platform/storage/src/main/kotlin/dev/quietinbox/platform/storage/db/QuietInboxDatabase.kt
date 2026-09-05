package dev.quietinbox.platform.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    /** Debug-only demo seeding/clearing. Adds no table and no column. */
    abstract fun demoDao(): DemoDao

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "quietinbox.vault"

        /**
         * v1 -> v2: deletion suppression is keyed by stable conversation identity instead of the
         * conversation row id (existing tokens are re-keyed through their conversation row, so a
         * deletion made before the upgrade still holds), and checkpoints remember the post time so
         * an active-notification resync is recognised as a repost. No user content is touched.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Re-key existing tokens through their conversation row so no suppression is lost.
                // The string must equal SourceScope.key + "#" + identityKey exactly.
                db.execSQL("ALTER TABLE deletion_suppression RENAME TO deletion_suppression_old")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deletion_suppression (" +
                        "scopeKey TEXT NOT NULL, fingerprint TEXT NOT NULL, expiresAtEpochMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(scopeKey, fingerprint))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO deletion_suppression (scopeKey, fingerprint, expiresAtEpochMs) " +
                        "SELECT c.packageName || '|' || c.profileKey || " +
                        "CASE WHEN c.accountKey IS NULL THEN '' ELSE '|' || c.accountKey END || '#' || c.identityKey, " +
                        "s.fingerprint, s.expiresAtEpochMs " +
                        "FROM deletion_suppression_old s JOIN conversation c ON c.id = s.conversationId",
                )
                db.execSQL("DROP TABLE deletion_suppression_old")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deletion_suppression_expiresAtEpochMs ON deletion_suppression (expiresAtEpochMs)")
                db.execSQL("ALTER TABLE notification_checkpoint ADD COLUMN postedAtEpochMs INTEGER")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
