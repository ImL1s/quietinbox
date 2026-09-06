package dev.quietinbox.platform.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM source_configuration ORDER BY addedAtEpochMs")
    fun observeAll(): Flow<List<SourceConfigurationEntity>>

    @Query("SELECT * FROM source_configuration ORDER BY addedAtEpochMs")
    suspend fun all(): List<SourceConfigurationEntity>

    @Query("SELECT * FROM source_configuration WHERE packageName = :packageName")
    suspend fun get(packageName: String): SourceConfigurationEntity?

    @Upsert
    suspend fun upsert(entity: SourceConfigurationEntity)

    @Query("UPDATE source_configuration SET enabled = :enabled WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)

    @Query("UPDATE source_configuration SET paused = :paused WHERE packageName = :packageName")
    suspend fun setPaused(packageName: String, paused: Boolean)

    @Query("UPDATE source_configuration SET retentionDays = :days WHERE packageName = :packageName")
    suspend fun setRetention(packageName: String, days: Int?)

    @Query("UPDATE source_configuration SET mediaEnabled = :enabled WHERE packageName = :packageName")
    suspend fun setMediaEnabled(packageName: String, enabled: Boolean)

    @Query("DELETE FROM source_configuration WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface JournalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EventJournalEntity): Long

    @Query("SELECT * FROM event_journal WHERE state = 'PENDING' ORDER BY receivedAtEpochMs LIMIT :limit")
    suspend fun pending(limit: Int): List<EventJournalEntity>

    /**
     * Pending rows except those of [excludedPackages] (paused sources wait; they must not occupy
     * the whole page and starve every other source, QI-SEC-001 round-10). Rows without a package
     * (pre-v3) are included and decided by the commit fence.
     */
    @Query("SELECT * FROM event_journal WHERE state = 'PENDING' AND (packageName IS NULL OR packageName NOT IN (:excludedPackages)) ORDER BY receivedAtEpochMs LIMIT :limit")
    suspend fun pendingExcluding(limit: Int, excludedPackages: List<String>): List<EventJournalEntity>

    /** A terminal state (anything but PENDING) also clears the payload: the text must not outlive its commit. */
    @Query(
        """
        UPDATE event_journal
        SET state = :state, attempts = attempts + 1, failureCode = :failure,
            payload = CASE WHEN :state = 'PENDING' THEN payload ELSE '' END
        WHERE eventId = :eventId
        """,
    )
    suspend fun setState(eventId: String, state: String, failure: String?)

    /** Pending rows of a source that was disabled or removed are discarded for good (QI-SEC-001). */
    @Query("UPDATE event_journal SET state = 'DISCARDED', failureCode = 'SOURCE_DISABLED', payload = '' WHERE state = 'PENDING' AND packageName = :packageName")
    suspend fun discardPending(packageName: String): Int

    @Query("SELECT payload FROM event_journal WHERE eventId = :eventId")
    suspend fun payload(eventId: String): String?

    @Query("SELECT attempts FROM event_journal WHERE eventId = :eventId")
    suspend fun attempts(eventId: String): Int?

    @Query("SELECT state FROM event_journal WHERE eventId = :eventId")
    suspend fun state(eventId: String): String?

    @Query("DELETE FROM event_journal WHERE expiresAtEpochMs < :now AND state != 'PENDING'")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM event_journal WHERE expiresAtEpochMs < :now")
    suspend fun deleteAllExpired(now: Long): Int

    @Query("SELECT COUNT(*) FROM event_journal WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM event_journal")
    suspend fun count(): Int

    @Query("DELETE FROM event_journal")
    suspend fun clear()
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM notification_checkpoint WHERE streamKey = :streamKey")
    suspend fun get(streamKey: String): CheckpointEntity?

    @Upsert
    suspend fun upsert(entity: CheckpointEntity)

    @Query("UPDATE notification_checkpoint SET closed = 1, updatedAtEpochMs = :now WHERE streamKey = :streamKey")
    suspend fun close(streamKey: String, now: Long)

    @Query("UPDATE notification_checkpoint SET closed = 1, updatedAtEpochMs = :now WHERE closed = 0")
    suspend fun closeAll(now: Long)

    @Query("DELETE FROM notification_checkpoint WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("DELETE FROM notification_checkpoint WHERE updatedAtEpochMs < :before")
    suspend fun deleteStale(before: Long): Int
}

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT * FROM conversation
        WHERE archived = :archived
          AND (:allPackages = 1 OR packageName IN (:packages))
        ORDER BY pinned DESC, lastActivityEpochMs DESC
        """,
    )
    fun observeInbox(archived: Boolean, allPackages: Boolean, packages: List<String>): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query(
        """
        SELECT * FROM conversation
        WHERE packageName = :packageName AND profileKey = :profileKey
          AND accountKey IS :accountKey AND identityKey = :identityKey
        """,
    )
    suspend fun find(packageName: String, profileKey: String, accountKey: String?, identityKey: String): ConversationEntity?

    @Insert
    suspend fun insert(entity: ConversationEntity): Long

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversation SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("UPDATE conversation SET lastViewedEpochMs = :now WHERE id = :id")
    suspend fun markViewed(id: Long, now: Long)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversation WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("SELECT COUNT(*) FROM conversation")
    fun observeCount(): Flow<Int>

    /** Conversations with copies newer than the last time they were opened, optionally limited to some sources. */
    @Query(
        """
        SELECT COUNT(*) FROM conversation
        WHERE archived = 0 AND messageCount > 0 AND lastActivityEpochMs > COALESCE(lastViewedEpochMs, 0)
          AND (:allPackages = 1 OR packageName IN (:packages))
        """,
    )
    suspend fun unviewedCount(allPackages: Boolean, packages: List<String>): Int

    /**
     * Recomputes the projection (counts, preview, last sender, last activity) from the messages
     * that are still visible at [now]. The single source of truth after any deletion, expiry or
     * restore (QI-DATA-004); the live commit path only increments.
     */
    @Query(
        """
        UPDATE conversation SET
          messageCount = (SELECT COUNT(*) FROM message m WHERE m.conversationId = conversation.id
                          AND m.dedupState != 'AMBIGUOUS_REPEAT' AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)),
          ambiguousCount = (SELECT COUNT(*) FROM message m WHERE m.conversationId = conversation.id
                          AND m.dedupState = 'AMBIGUOUS_REPEAT' AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)),
          lastMessagePreview = (SELECT substr(m.body, 1, 200) FROM message m WHERE m.conversationId = conversation.id
                          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now) ORDER BY m.sortKey DESC, m.id DESC LIMIT 1),
          lastSenderName = (SELECT m.senderName FROM message m WHERE m.conversationId = conversation.id
                          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now) ORDER BY m.sortKey DESC, m.id DESC LIMIT 1),
          lastActivityEpochMs = COALESCE((SELECT MAX(m.observedAtEpochMs) FROM message m WHERE m.conversationId = conversation.id
                          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)), conversation.createdAtEpochMs)
        WHERE id IN (:ids)
        """,
    )
    suspend fun rebuildProjection(ids: List<Long>, now: Long)

    @Query("SELECT id FROM conversation WHERE messageCount = 0 AND createdAtEpochMs < :before")
    suspend fun emptyOlderThan(before: Long): List<Long>

    @Query("SELECT DISTINCT packageName FROM conversation")
    fun observePackages(): Flow<List<String>>

    @Query("SELECT * FROM conversation WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, limit: Int): List<ConversationEntity>

    @Query("SELECT COUNT(*) FROM conversation")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    /**
     * Expired rows are hidden here, not only deleted by retention later (QI-DATA-007). [now] is
     * fixed when the flow is collected; a screen that stays open across an expiry boundary shows
     * the row until it is re-collected or retention removes it.
     */
    @Query(
        """
        SELECT * FROM message WHERE conversationId = :conversationId
          AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)
        ORDER BY sortKey ASC, id ASC
        """,
    )
    fun observeForConversation(conversationId: Long, now: Long): Flow<List<MessageEntity>>

    @Query("SELECT DISTINCT conversationId FROM message WHERE id IN (:ids)")
    suspend fun conversationIdsOf(ids: List<Long>): List<Long>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM message WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId AND sourceMessageId = :sourceMessageId LIMIT 1")
    suspend fun findBySourceId(conversationId: Long, sourceMessageId: String): MessageEntity?

    @Query("SELECT id FROM message WHERE conversationId = :conversationId AND fingerprint = :fingerprint ORDER BY id DESC LIMIT 1")
    suspend fun findIdByFingerprint(conversationId: Long, fingerprint: String): Long?

    /** The newest [limit] stored rows with this fingerprint, newest first (checkpoint-loss guard, one link per row). */
    @Query("SELECT id FROM message WHERE conversationId = :conversationId AND fingerprint = :fingerprint ORDER BY id DESC LIMIT :limit")
    suspend fun findLatestIdsByFingerprint(conversationId: Long, fingerprint: String, limit: Int): List<Long>

    @Insert
    suspend fun insert(entity: MessageEntity): Long

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("UPDATE message SET observationCount = observationCount + 1 WHERE id = :id")
    suspend fun incrementObservation(id: Long)

    @Query("UPDATE message SET body = :body, revisionCount = revisionCount + 1, eventId = :eventId WHERE id = :id")
    suspend fun applyRevision(id: Long, body: String, eventId: String)

    @Query("UPDATE message SET mediaState = :state, mediaBlobId = :blobId WHERE id = :id")
    suspend fun setMedia(id: Long, state: String, blobId: Long?)

    @Query("DELETE FROM message WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT id FROM message WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs < :now LIMIT :limit")
    suspend fun expiredIds(now: Long, limit: Int): List<Long>

    @Query("UPDATE message SET expiresAtEpochMs = observedAtEpochMs + :ttlMs WHERE conversationId IN (SELECT id FROM conversation WHERE packageName = :packageName)")
    suspend fun recomputeExpiryForPackage(packageName: String, ttlMs: Long)

    @Query("UPDATE message SET expiresAtEpochMs = observedAtEpochMs + :ttlMs")
    suspend fun recomputeExpiryAll(ttlMs: Long)

    @Query("SELECT COUNT(*) FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now")
    fun observeCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM message WHERE dedupState = 'AMBIGUOUS_REPEAT' AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)")
    fun observeAmbiguousCount(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM message WHERE mediaState = 'PENDING'")
    suspend fun pendingMediaCount(): Int

    @Query("SELECT * FROM message WHERE mediaState = 'PENDING' ORDER BY observedAtEpochMs LIMIT :limit")
    suspend fun pendingMedia(limit: Int): List<MessageEntity>

    /**
     * Newest [limit] rows of the period, newest first. No index is led by `sortKey`, so SQLite scans
     * the period and sorts at most [limit] rows: CPU traded for a bounded heap on purpose (the caller
     * samples vault changes at 400 ms). A `sortKey` index is a candidate for schema v3.
     */
    @Query(
        """
        SELECT m.conversationId, m.sortKey, m.dedupState, m.contentStatus, m.body, m.senderName, m.senderKey, m.isSelf, c.packageName
        FROM message m JOIN conversation c ON c.id = m.conversationId
        WHERE m.sortKey >= :since AND m.sortKey <= :until
          AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)
        ORDER BY m.sortKey DESC
        LIMIT :limit
        """,
    )
    suspend fun statsBetween(since: Long, until: Long, limit: Int, now: Long): List<MessageStatRow>

    /** Export pages: keyset by id, and never a copy that is already expired (QI-DATA-007 round-10). */
    @Query("SELECT * FROM message WHERE id > :afterId AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now) ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, limit: Int, now: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now")
    suspend fun exportCount(now: Long): Int

    @Query("SELECT MIN(sortKey) FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now")
    suspend fun earliestSortKey(now: Long): Long?
}

@Dao
interface RevisionDao {
    @Insert
    suspend fun insert(entity: MessageRevisionEntity): Long

    @Query("SELECT * FROM message_revision WHERE messageId = :messageId ORDER BY observedAtEpochMs")
    fun observeForMessage(messageId: Long): Flow<List<MessageRevisionEntity>>

    @Query("SELECT * FROM message_revision WHERE id > :afterId AND messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now) ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, limit: Int, now: Long): List<MessageRevisionEntity>

    @Query("SELECT COUNT(*) FROM message_revision WHERE messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)")
    suspend fun exportCount(now: Long): Int
}

@Dao
interface ObservationLinkDao {
    @Insert
    suspend fun insert(entity: ObservationLinkEntity): Long

    @Query("SELECT COUNT(*) FROM observation_link WHERE kind = :kind")
    suspend fun countByKind(kind: String): Int
}

@Dao
interface MediaDao {
    @Insert
    suspend fun insert(entity: MediaBlobEntity): Long

    @Query("SELECT * FROM media_blob WHERE id = :id")
    suspend fun get(id: Long): MediaBlobEntity?

    /** Blobs whose message is gone, or which the message no longer points at (a link that never completed). */
    @Query("SELECT b.* FROM media_blob b LEFT JOIN message m ON m.id = b.messageId WHERE m.id IS NULL OR m.mediaBlobId IS NULL OR m.mediaBlobId != b.id")
    suspend fun orphans(): List<MediaBlobEntity>

    @Query("SELECT * FROM media_blob WHERE messageId IN (:messageIds)")
    suspend fun forMessages(messageIds: List<Long>): List<MediaBlobEntity>

    @Query("SELECT * FROM media_blob WHERE messageId IN (SELECT id FROM message WHERE conversationId = :conversationId)")
    suspend fun forConversation(conversationId: Long): List<MediaBlobEntity>

    @Query(
        """
        SELECT b.* FROM media_blob b JOIN message m ON m.id = b.messageId
        JOIN conversation c ON c.id = m.conversationId WHERE c.packageName = :packageName
        """,
    )
    suspend fun forPackage(packageName: String): List<MediaBlobEntity>

    @Query("DELETE FROM media_blob WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE media_blob SET messageId = :messageId WHERE id = :blobId")
    suspend fun setMessageId(blobId: Long, messageId: Long)

    @Query("SELECT COALESCE(SUM(byteCount), 0) FROM media_blob")
    fun observeTotalBytes(): Flow<Long>

    /** Highest blob id at the time of the call; an export bounds its media pages to it so a picture committed later is never exported without its message. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM media_blob")
    suspend fun maxId(): Long

    @Query("SELECT * FROM media_blob WHERE id > :afterId AND id <= :maxId AND messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now) ORDER BY id LIMIT :limit")
    suspend fun exportPage(afterId: Long, maxId: Long, limit: Int, now: Long): List<MediaBlobEntity>

    @Query("SELECT COUNT(*) FROM media_blob WHERE messageId IN (SELECT id FROM message WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now)")
    suspend fun exportCount(now: Long): Int
}

@Dao
interface SuppressionDao {
    @Query("SELECT COUNT(*) FROM deletion_suppression WHERE scopeKey = :scopeKey AND fingerprint = :fingerprint AND expiresAtEpochMs > :now")
    suspend fun isSuppressed(scopeKey: String, fingerprint: String, now: Long): Int

    @Query("SELECT * FROM deletion_suppression WHERE scopeKey = :scopeKey AND fingerprint = :fingerprint AND expiresAtEpochMs > :now LIMIT 1")
    suspend fun token(scopeKey: String, fingerprint: String, now: Long): DeletionSuppressionEntity?

    @Upsert
    suspend fun upsert(entity: DeletionSuppressionEntity)

    @Query("DELETE FROM deletion_suppression WHERE expiresAtEpochMs < :now")
    suspend fun deleteExpired(now: Long): Int

    /** Exact prefix match (no LIKE: a package name may contain `_`, a LIKE wildcard). */
    @Query("DELETE FROM deletion_suppression WHERE substr(scopeKey, 1, length(:prefix)) = :prefix")
    suspend fun deleteForScopePrefix(prefix: String): Int
}

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTokens(tokens: List<SearchTokenEntity>)

    @Query("DELETE FROM search_token WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: Long)

    @Query(
        """
        SELECT m.* FROM message m
        WHERE m.id IN (
            SELECT messageId FROM search_token WHERE token IN (:tokens)
            GROUP BY messageId HAVING COUNT(DISTINCT token) = :tokenCount
        )
        AND (:allPackages = 1 OR m.conversationId IN (SELECT id FROM conversation WHERE packageName IN (:packages)))
        AND (:fromMs IS NULL OR m.sortKey >= :fromMs)
        AND (:toMs IS NULL OR m.sortKey <= :toMs)
        AND (m.expiresAtEpochMs IS NULL OR m.expiresAtEpochMs > :now)
        AND (m.sortKey < :beforeSortKey OR (m.sortKey = :beforeSortKey AND m.id < :beforeId))
        ORDER BY m.sortKey DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchCandidates(
        tokens: List<String>,
        tokenCount: Int,
        allPackages: Boolean,
        packages: List<String>,
        fromMs: Long?,
        toMs: Long?,
        beforeSortKey: Long,
        beforeId: Long,
        limit: Int,
        now: Long,
    ): List<MessageEntity>
}

@Dao
interface HealthDao {
    @Insert
    suspend fun insertSession(entity: CaptureSessionEntity): Long

    @Query("UPDATE capture_session SET endedAtEpochMs = :endedAt, endReason = :reason WHERE id = :id")
    suspend fun endSession(id: Long, endedAt: Long?, reason: String?)

    @Query("SELECT * FROM capture_session ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeSessions(limit: Int): Flow<List<CaptureSessionEntity>>

    @Query("SELECT * FROM capture_session WHERE endedAtEpochMs IS NULL AND id != :exceptId ORDER BY startedAtEpochMs DESC")
    suspend fun openSessionsExcept(exceptId: Long): List<CaptureSessionEntity>

    @Insert
    suspend fun insertGap(entity: GapIntervalEntity): Long

    @Query("UPDATE gap_interval SET endEpochMs = :endEpochMs WHERE id = :id")
    suspend fun closeGap(id: Long, endEpochMs: Long?)

    @Query("SELECT * FROM gap_interval ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeGaps(limit: Int): Flow<List<GapIntervalEntity>>

    @Query("SELECT * FROM gap_interval WHERE endEpochMs IS NULL AND reason IN (:reasons)")
    suspend fun openGaps(reasons: List<String>): List<GapIntervalEntity>

    @Query("DELETE FROM gap_interval WHERE createdAtEpochMs < :before")
    suspend fun deleteGapsBefore(before: Long): Int

    @Query("DELETE FROM capture_session WHERE startedAtEpochMs < :before AND endedAtEpochMs IS NOT NULL")
    suspend fun deleteSessionsBefore(before: Long): Int

    @Insert
    suspend fun insertSummary(entity: SummaryObservationEntity): Long

    @Query("SELECT COUNT(*) FROM summary_observation WHERE observedAtEpochMs >= :since")
    suspend fun summaryCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM summary_observation WHERE observedAtEpochMs >= :since AND observedAtEpochMs <= :until")
    suspend fun summaryCountBetween(since: Long, until: Long): Int

    @Query("SELECT COUNT(*) FROM summary_observation")
    fun observeSummaryCount(): Flow<Int>

    @Query("DELETE FROM summary_observation WHERE observedAtEpochMs < :before")
    suspend fun deleteSummariesBefore(before: Long): Int

    @Query("DELETE FROM summary_observation WHERE packageName = :packageName")
    suspend fun deleteSummariesForPackage(packageName: String): Int
}

@Dao
interface DiagnosticsDao {
    @Insert
    suspend fun insert(entity: DiagnosticEventEntity): Long

    @Query("SELECT * FROM local_diagnostic_event ORDER BY atEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT code, COUNT(*) AS n FROM local_diagnostic_event WHERE atEpochMs >= :since GROUP BY code ORDER BY n DESC")
    suspend fun countsSince(since: Long): List<DiagnosticCount>

    @Query("DELETE FROM local_diagnostic_event WHERE atEpochMs < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM local_diagnostic_event WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String): Int
}

data class DiagnosticCount(val code: String, val n: Int)

/**
 * Queries used only by the debug demo seeder (`DemoDataRepository`, debug source set). They stay in
 * the main Room class because a DAO cannot be variant-specific; they carry no demo content. They address rows by the
 * recognisable demo tags — a `demo.quietinbox.` package prefix, or a `demo-` capture generation —
 * so seeding and clearing can never touch captured data. No schema change: every column already
 * exists.
 */
@Dao
interface DemoDao {
    // Deletion order matters: media_blob has no cascade, so it is cleared through the join while
    // the conversations still exist.
    @Query(
        """
        DELETE FROM media_blob WHERE messageId IN (
            SELECT m.id FROM message m JOIN conversation c ON c.id = m.conversationId
            WHERE c.packageName LIKE :packagePrefix
        )
        """,
    )
    suspend fun deleteMediaBlobs(packagePrefix: String): Int

    @Query("DELETE FROM deletion_suppression WHERE scopeKey LIKE :packagePrefix")
    suspend fun deleteSuppression(packagePrefix: String): Int

    @Query("DELETE FROM notification_checkpoint WHERE packageName LIKE :packagePrefix")
    suspend fun deleteCheckpoints(packagePrefix: String): Int

    /** Cascades to message, and from there to message_revision, observation_link and search_token. */
    @Query("DELETE FROM conversation WHERE packageName LIKE :packagePrefix")
    suspend fun deleteConversations(packagePrefix: String): Int

    @Query("DELETE FROM source_configuration WHERE packageName LIKE :packagePrefix")
    suspend fun deleteSources(packagePrefix: String): Int

    @Query("DELETE FROM local_diagnostic_event WHERE packageName LIKE :packagePrefix")
    suspend fun deleteDiagnostics(packagePrefix: String): Int

    @Query("DELETE FROM summary_observation WHERE packageName LIKE :packagePrefix")
    suspend fun deleteSummaries(packagePrefix: String): Int

    /**
     * `gap_interval` has no taggable column (`reason` and `precision` are mapped onto enums, so an
     * invented value would render as "unknown"), hence demo gaps are stamped with the demo
     * session's start time and deleted through that join — before the sessions themselves.
     */
    @Query(
        "DELETE FROM gap_interval WHERE createdAtEpochMs IN " +
            "(SELECT startedAtEpochMs FROM capture_session WHERE generation LIKE :generationPrefix)",
    )
    suspend fun deleteGaps(generationPrefix: String): Int

    @Query("DELETE FROM capture_session WHERE generation LIKE :generationPrefix")
    suspend fun deleteSessions(generationPrefix: String): Int

    @Query("SELECT COUNT(*) FROM source_configuration WHERE packageName LIKE :packagePrefix")
    suspend fun countSources(packagePrefix: String): Int

    @Query("SELECT COUNT(*) FROM conversation WHERE packageName LIKE :packagePrefix")
    suspend fun countConversations(packagePrefix: String): Int

    @Query(
        "SELECT COUNT(*) FROM message m JOIN conversation c ON c.id = m.conversationId " +
            "WHERE c.packageName LIKE :packagePrefix",
    )
    suspend fun countMessages(packagePrefix: String): Int

    @Query(
        "SELECT COUNT(*) FROM message m JOIN conversation c ON c.id = m.conversationId " +
            "WHERE c.packageName LIKE :packagePrefix AND m.dedupState = :dedupState",
    )
    suspend fun countMessagesWithDedupState(packagePrefix: String, dedupState: String): Int

    @Query(
        "SELECT COUNT(*) FROM message_revision r JOIN message m ON m.id = r.messageId " +
            "JOIN conversation c ON c.id = m.conversationId WHERE c.packageName LIKE :packagePrefix",
    )
    suspend fun countRevisions(packagePrefix: String): Int

    @Query(
        "SELECT COUNT(*) FROM observation_link l JOIN message m ON m.id = l.messageId " +
            "JOIN conversation c ON c.id = m.conversationId WHERE c.packageName LIKE :packagePrefix AND l.kind = :kind",
    )
    suspend fun countObservationLinks(packagePrefix: String, kind: String): Int

    @Query(
        "SELECT COUNT(*) FROM search_token t JOIN message m ON m.id = t.messageId " +
            "JOIN conversation c ON c.id = m.conversationId WHERE c.packageName LIKE :packagePrefix",
    )
    suspend fun countSearchTokens(packagePrefix: String): Int

    @Query("SELECT COUNT(*) FROM capture_session WHERE generation LIKE :generationPrefix")
    suspend fun countSessions(generationPrefix: String): Int

    @Query("SELECT COUNT(*) FROM local_diagnostic_event WHERE packageName LIKE :packagePrefix")
    suspend fun countDiagnostics(packagePrefix: String): Int

    @Query("SELECT COUNT(*) FROM gap_interval")
    suspend fun countAllGaps(): Int
}
