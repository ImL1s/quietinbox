package dev.quietinbox.platform.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema migrations run against the exported schema JSON (plan section 8 forbids destructive
 * migration). The SQL is engine-agnostic, so the framework SQLite driver is enough here; the
 * SQLCipher path is covered by [VaultRoundTripTest].
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        QuietInboxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2ReKeysExistingSuppressionTokens() {
        val name = "$dbName-rekey"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO conversation (id, packageName, profileKey, accountKey, identityKey, identityConfidence, title, isGroup, pinned, archived, createdAtEpochMs, lastActivityEpochMs, lastViewedEpochMs, messageCount, ambiguousCount, summaryOnlyCount, lastMessagePreview, lastSenderName) " +
                    "VALUES (7, 'jp.naver.line.android', 'user:0', NULL, 'shortcut:abc', 'INFERRED_FROM_STREAM', 'A', NULL, 0, 0, 1, 1, NULL, 0, 0, 0, NULL, NULL)",
            )
            db.execSQL("INSERT INTO deletion_suppression (conversationId, fingerprint, expiresAtEpochMs) VALUES (7, 'fp-deleted', 99)")
        }
        val migrated = helper.runMigrationsAndValidate(name, 2, true, QuietInboxDatabase.MIGRATION_1_2)
        migrated.query("SELECT scopeKey, fingerprint, expiresAtEpochMs FROM deletion_suppression").use { c ->
            c.moveToFirst() shouldBe true
            c.getString(0) shouldBe "jp.naver.line.android|user:0#shortcut:abc"
            c.getString(1) shouldBe "fp-deleted"
            c.getLong(2) shouldBe 99L
        }
        migrated.close()
    }

    @Test
    fun migrate1To2RecreatesSuppressionKeyedByIdentity() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL("INSERT INTO deletion_suppression (conversationId, fingerprint, expiresAtEpochMs) VALUES (5, 'fp', 1)")
            db.execSQL(
                "INSERT INTO conversation (packageName, profileKey, accountKey, identityKey, identityConfidence, title, isGroup, pinned, archived, createdAtEpochMs, lastActivityEpochMs, lastViewedEpochMs, messageCount, ambiguousCount, summaryOnlyCount, lastMessagePreview, lastSenderName) " +
                    "VALUES ('pkg', 'user:0', NULL, 'title:A', 'UNRESOLVED', 'A', NULL, 0, 0, 1, 1, NULL, 0, 0, 0, NULL, NULL)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, QuietInboxDatabase.MIGRATION_1_2)
        // The pre-upgrade token (conversationId 5 did not exist) is dropped; nothing else to keep.
        migrated.query("SELECT COUNT(*) FROM deletion_suppression").use { c ->
            c.moveToFirst() shouldBe true
            c.getInt(0) shouldBe 0
        }
        // User content is untouched by the migration.
        migrated.query("SELECT COUNT(*) FROM conversation").use { c ->
            c.moveToFirst() shouldBe true
            c.getInt(0) shouldBe 1
        }
        migrated.execSQL("INSERT INTO deletion_suppression (scopeKey, fingerprint, expiresAtEpochMs) VALUES ('pkg|user:0#title:A', 'fp', 1)")
        migrated.close()
    }
}
