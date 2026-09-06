package dev.quietinbox.platform.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One newline-delimited JSON record inside the encrypted stream. Order: manifest, data..., end. */
@Serializable
sealed interface BackupRecord {
    @Serializable
    @SerialName("manifest")
    data class Manifest(
        val formatVersion: Int,
        val schemaVersion: Int,
        val appVersion: String,
        val createdAtEpochMs: Long,
        val expected: Counts,
    ) : BackupRecord

    @Serializable
    @SerialName("source")
    data class Source(
        val packageName: String,
        val displayName: String,
        val enabled: Boolean,
        val paused: Boolean,
        val retentionDays: Int?,
        val mediaEnabled: Boolean,
        val addedAtEpochMs: Long,
        val adapterId: String?,
    ) : BackupRecord

    @Serializable
    @SerialName("conversation")
    data class Conversation(
        val id: Long,
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
    ) : BackupRecord

    @Serializable
    @SerialName("message")
    data class Message(
        val id: Long,
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
        val mediaMimeType: String?,
        val fingerprint: String,
        val sortKey: Long,
        val expiresAtEpochMs: Long?,
    ) : BackupRecord

    @Serializable
    @SerialName("revision")
    data class Revision(
        val messageId: Long,
        val body: String,
        val observedAtEpochMs: Long,
    ) : BackupRecord

    @Serializable
    @SerialName("media")
    data class Media(
        val id: Long,
        val messageId: Long?,
        val mimeType: String?,
        val width: Int?,
        val height: Int?,
        val createdAtEpochMs: Long,
        /** Raw bytes, base64; bounded by [BackupLimits.MAX_MEDIA_BYTES]. */
        val dataBase64: String,
    ) : BackupRecord

    @Serializable
    @SerialName("end")
    /** [skippedMedia]: media rows the exporter could not read; the manifest's media count includes them. */
    data class End(val actual: Counts, val skippedMedia: Int = 0) : BackupRecord
}

@Serializable
data class Counts(
    val sources: Int = 0,
    val conversations: Int = 0,
    val messages: Int = 0,
    val revisions: Int = 0,
    val media: Int = 0,
)

object BackupLimits {
    /** Upper bound per record line in UTF-16 code units, enforced while reading (not after). */
    const val MAX_LINE_CHARS = 16 * 1024 * 1024
    const val MAX_RECORDS = 2_000_000
    const val MAX_MEDIA_BYTES = 8L * 1024 * 1024
    const val MAX_STAGED_MEDIA_BYTES = 256L * 1024 * 1024

    /** Total characters of non-media records held in memory while staging a restore (≈ 32 MB of UTF-16 plus record overhead). */
    const val MAX_STAGED_TEXT_CHARS = 16L * 1024 * 1024
}
