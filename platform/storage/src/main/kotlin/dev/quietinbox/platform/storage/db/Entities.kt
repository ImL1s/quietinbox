package dev.quietinbox.platform.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Per-source configuration (plan section 8: SourceConfiguration). */
@Entity(tableName = "source_configuration")
data class SourceConfigurationEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val enabled: Boolean,
    val paused: Boolean,
    /** Null = use the global default. */
    val retentionDays: Int?,
    val mediaEnabled: Boolean,
    val addedAtEpochMs: Long,
    val adapterId: String?,
)

/** A listener connection epoch. */
@Entity(tableName = "capture_session")
data class CaptureSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val generation: String,
    val bootSessionId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val endReason: String?,
)

@Entity(tableName = "gap_interval", indices = [Index("startEpochMs")])
data class GapIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val reason: String,
    val precision: String,
    val createdAtEpochMs: Long,
)

/** Durable, short-TTL copy of accepted input. Committed rows are pruned by retention. */
@Entity(tableName = "event_journal", indices = [Index("state"), Index("expiresAtEpochMs")])
data class EventJournalEntity(
    @PrimaryKey val eventId: String,
    val generation: String,
    val receivedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /** PENDING, COMMITTED, FAILED, SKIPPED */
    val state: String,
    val attempts: Int,
    val failureCode: String?,
    /** JSON of [dev.quietinbox.core.model.NotificationSnapshot]. */
    val payload: String,
)

/** Last visible window per notification stream (plan section 8: NotificationCheckpoint). */
@Entity(tableName = "notification_checkpoint")
data class CheckpointEntity(
    @PrimaryKey val streamKey: String,
    val packageName: String,
    val notificationKey: String,
    /** JSON list of window items. */
    val windowJson: String,
    val closed: Boolean,
    val parserId: String,
    val parserVersion: String,
    val generation: String,
    val updatedAtEpochMs: Long,
    /** Post time of the notification that produced the window (resync detection). */
    val postedAtEpochMs: Long? = null,
)

@Entity(
    tableName = "conversation",
    indices = [
        Index(value = ["packageName", "profileKey", "accountKey", "identityKey"], unique = true),
        Index("lastActivityEpochMs"),
        Index("archived"),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val profileKey: String,
    val accountKey: String?,
    val identityKey: String,
    val identityConfidence: String,
    val title: String?,
    val isGroup: Boolean?,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAtEpochMs: Long,
    val lastActivityEpochMs: Long,
    val lastViewedEpochMs: Long?,
    val messageCount: Int,
    val ambiguousCount: Int,
    /** Reserved: summary-only observations are not attributable to a conversation in v0.1, so this stays 0. */
    val summaryOnlyCount: Int,
    val lastMessagePreview: String?,
    val lastSenderName: String?,
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["conversationId", "sortKey"]),
        Index(value = ["conversationId", "sourceMessageId"]),
        Index("fingerprint"),
        Index("expiresAtEpochMs"),
        Index("observedAtEpochMs"),
        Index("mediaState"),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val sourceMessageId: String?,
    val senderName: String?,
    val senderKey: String?,
    val isSelf: Boolean,
    val body: String,
    val kind: String,
    val sourceTimestampEpochMs: Long?,
    val timestampQuality: String,
    val observedAtEpochMs: Long,
    val postedAtEpochMs: Long?,
    val origin: String,
    val contentStatus: String,
    val dedupState: String,
    val revisionCount: Int,
    val observationCount: Int,
    val mediaState: String,
    val mediaBlobId: Long?,
    val mediaUri: String?,
    val mediaMimeType: String?,
    val fingerprint: String,
    val eventId: String,
    val sortKey: Long,
    val expiresAtEpochMs: Long?,
)

@Entity(
    tableName = "message_revision",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class MessageRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val body: String,
    val observedAtEpochMs: Long,
    val eventId: String,
)

/** Links extra observations (re-posts, stale windows, ambiguous repeats) to a stored message. */
@Entity(
    tableName = "observation_link",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class ObservationLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val eventId: String,
    /** REPOST, STALE_WINDOW, SAME_ID, AMBIGUOUS_REPEAT */
    val kind: String,
    val observedAtEpochMs: Long,
)

@Entity(tableName = "media_blob", indices = [Index("messageId"), Index("state")])
data class MediaBlobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long?,
    /** Opaque file name inside files/media; never derived from content. */
    val fileName: String,
    val thumbFileName: String?,
    val mimeType: String?,
    val byteCount: Long,
    val width: Int?,
    val height: Int?,
    val state: String,
    val failureReason: String?,
    val createdAtEpochMs: Long,
)

/**
 * Prevents replay of active notifications from resurrecting user-deleted content. Keyed by the
 * stable conversation identity (`SourceScope.key + "#" + identityKey`), not the row id, so it still
 * applies after the whole conversation row was deleted and a replay re-creates it.
 */
@Entity(tableName = "deletion_suppression", primaryKeys = ["scopeKey", "fingerprint"], indices = [Index("expiresAtEpochMs")])
data class DeletionSuppressionEntity(
    val scopeKey: String,
    val fingerprint: String,
    val expiresAtEpochMs: Long,
)

@Entity(
    tableName = "search_token",
    primaryKeys = ["token", "messageId"],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class SearchTokenEntity(
    val token: String,
    val messageId: Long,
)

/** Summary-only observations ("5 new messages") that have no conversation body. */
@Entity(tableName = "summary_observation", indices = [Index("observedAtEpochMs")])
data class SummaryObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val observedAtEpochMs: Long,
    val messageCount: Int?,
    val conversationCount: Int?,
    val eventId: String,
)

/** Body-free diagnostics: codes, versions and counts only. */
@Entity(tableName = "local_diagnostic_event", indices = [Index("atEpochMs")])
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val detail: String?,
    val packageName: String?,
    val atEpochMs: Long,
)

/** Row shape for analytics: no ids are needed beyond conversation grouping. */
data class MessageStatRow(
    val conversationId: Long,
    val sortKey: Long,
    val dedupState: String,
    val contentStatus: String,
    val body: String,
    val senderName: String?,
    val isSelf: Boolean,
    @ColumnInfo(name = "packageName") val packageName: String,
)
