package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import dev.quietinbox.core.identity.ConversationIdentity
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.ParsedBatch
import dev.quietinbox.core.model.SearchNormalizer
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.core.reconcile.Decision
import dev.quietinbox.core.reconcile.KnownKind
import dev.quietinbox.core.reconcile.KnownMessage
import dev.quietinbox.core.reconcile.MessageWindow
import dev.quietinbox.core.reconcile.ReconcileNote
import dev.quietinbox.core.reconcile.ReconcileResult
import dev.quietinbox.core.reconcile.WindowItem
import dev.quietinbox.platform.storage.db.CheckpointEntity
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DiagnosticEventEntity
import dev.quietinbox.platform.storage.db.EventJournalEntity
import dev.quietinbox.platform.storage.db.MessageEntity
import dev.quietinbox.platform.storage.db.MessageRevisionEntity
import dev.quietinbox.platform.storage.db.ObservationLinkEntity
import dev.quietinbox.platform.storage.db.SearchTokenEntity
import dev.quietinbox.platform.storage.db.SummaryObservationEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a committed snapshot; ids are used to kick off media work. */
data class CommitOutcome(
    val conversationId: Long?,
    val newMessageIds: List<Long>,
    val ambiguousMessageIds: List<Long>,
    val pendingMediaMessageIds: List<Long>,
    val suppressedCount: Int,
    val summaryRecorded: Boolean,
)

@Serializable
private data class WindowItemJson(val fp: String, val sid: String? = null, val mid: Long? = null)

/**
 * Everything the capture pipeline needs from the vault: journal durability, checkpoints, id
 * lookups and the single-transaction projection commit (plan section 5).
 */
@Singleton
class IngestRepository @Inject constructor(
    private val holder: DatabaseHolder,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
    private val windowSerializer = ListSerializer(WindowItemJson.serializer())

    /** Durable acceptance: the snapshot is only "accepted" once this returns. */
    suspend fun journal(snapshot: NotificationSnapshot, generation: String, ttlMs: Long): Boolean {
        val db = holder.db()
        val row = EventJournalEntity(
            eventId = snapshot.eventId,
            generation = generation,
            receivedAtEpochMs = snapshot.observedAtEpochMs,
            expiresAtEpochMs = snapshot.observedAtEpochMs + ttlMs,
            state = "PENDING",
            attempts = 0,
            failureCode = null,
            payload = json.encodeToString(NotificationSnapshot.serializer(), snapshot),
        )
        return db.journalDao().insert(row) != -1L
    }

    suspend fun isJournalPending(eventId: String): Boolean = holder.db().journalDao().state(eventId) == "PENDING"

    suspend fun pendingJournal(limit: Int = 200): List<Pair<String, NotificationSnapshot>> {
        val db = holder.db()
        return db.journalDao().pending(limit).mapNotNull { row ->
            runCatching { row.generation to json.decodeFromString(NotificationSnapshot.serializer(), row.payload) }
                .getOrElse {
                    db.journalDao().setState(row.eventId, "FAILED", "DECODE")
                    null
                }
        }
    }

    suspend fun markJournal(eventId: String, state: String, failure: String? = null) {
        holder.db().journalDao().setState(eventId, state, failure)
    }

    /**
     * A failed commit stays PENDING (so replay retries it) until [MAX_ATTEMPTS] is reached; only
     * then is it marked FAILED. Transient errors therefore never lose an accepted event.
     */
    suspend fun markJournalRetryable(eventId: String, failure: String) {
        val db = holder.db()
        val attempts = (db.journalDao().attempts(eventId) ?: 0) + 1
        db.journalDao().setState(eventId, if (attempts >= MAX_ATTEMPTS) "FAILED" else "PENDING", failure)
    }

    suspend fun checkpoint(streamKey: String): MessageWindow? {
        val row = holder.db().checkpointDao().get(streamKey) ?: return null
        val items = runCatching { json.decodeFromString(windowSerializer, row.windowJson) }.getOrDefault(emptyList())
        return MessageWindow(row.notificationKey, items.map { WindowItem(it.fp, it.sid, it.mid) }, row.closed, row.postedAtEpochMs)
    }

    suspend fun closeWindow(streamKey: String, now: Long) {
        holder.db().checkpointDao().close(streamKey, now)
    }

    suspend fun closeAllWindows(now: Long) {
        holder.db().checkpointDao().closeAll(now)
    }

    suspend fun lookupById(conversationId: Long?, sourceMessageId: String): KnownMessage? {
        if (conversationId == null) return null
        val m = holder.db().messageDao().findBySourceId(conversationId, sourceMessageId) ?: return null
        return KnownMessage(m.id, m.fingerprint, m.body)
    }

    suspend fun findConversationId(identity: ConversationIdentity): Long? =
        holder.db().conversationDao().find(identity.scope.packageName, identity.scope.profileKey, identity.scope.accountKey, identity.identityKey)?.id

    suspend fun diagnostic(code: String, detail: String? = null, packageName: String? = null, now: Long) {
        runCatching { holder.db().diagnosticsDao().insert(DiagnosticEventEntity(code = code, detail = detail, packageName = packageName, atEpochMs = now)) }
    }

    /**
     * Applies parser + reconciler output atomically. Never deletes; never rewrites source times.
     * A message whose fingerprint the user deleted (suppression) is skipped and counted.
     */
    suspend fun commit(
        snapshot: NotificationSnapshot,
        batch: ParsedBatch,
        identity: ConversationIdentity?,
        reconcile: ReconcileResult?,
        generation: String,
        retentionMs: Long?,
        mediaAllowed: Boolean,
    ): CommitOutcome {
        val db = holder.db()
        val now = snapshot.observedAtEpochMs
        return db.withTransaction {
            var summaryRecorded = false
            if (batch.summary != null) {
                db.healthDao().insertSummary(
                    SummaryObservationEntity(
                        packageName = snapshot.source.packageName,
                        observedAtEpochMs = now,
                        messageCount = batch.summary?.messageCount,
                        conversationCount = batch.summary?.conversationCount,
                        eventId = snapshot.eventId,
                    ),
                )
                summaryRecorded = true
            }

            if (identity == null || reconcile == null || reconcile.decisions.isEmpty()) {
                db.journalDao().setState(snapshot.eventId, "COMMITTED", null)
                return@withTransaction CommitOutcome(null, emptyList(), emptyList(), emptyList(), 0, summaryRecorded)
            }

            val convDao = db.conversationDao()
            val existing = convDao.find(identity.scope.packageName, identity.scope.profileKey, identity.scope.accountKey, identity.identityKey)
            val conversationId = existing?.id ?: convDao.insert(
                ConversationEntity(
                    packageName = identity.scope.packageName,
                    profileKey = identity.scope.profileKey,
                    accountKey = identity.scope.accountKey,
                    identityKey = identity.identityKey,
                    identityConfidence = identity.confidence.name,
                    title = identity.displayTitle,
                    isGroup = batch.conversation?.isGroup,
                    pinned = false,
                    archived = false,
                    createdAtEpochMs = now,
                    lastActivityEpochMs = now,
                    lastViewedEpochMs = null,
                    messageCount = 0,
                    ambiguousCount = 0,
                    summaryOnlyCount = 0,
                    lastMessagePreview = null,
                    lastSenderName = null,
                ),
            )

            val suppressionKey = suppressionScopeKey(identity.scope, identity.identityKey)
            // Checkpoint-loss guard input: fingerprints that existed BEFORE this batch (so equal
            // items inside one window keep their multiplicity).
            val preExisting: Map<String, Long> = if (ReconcileNote.NO_PREVIOUS_WINDOW in reconcile.notes) {
                reconcile.decisions.filter { it is Decision.New && !it.confirmedById }
                    .map { it.fingerprint }.distinct()
                    .mapNotNull { fp -> db.messageDao().findIdByFingerprint(conversationId, fp)?.let { fp to it } }
                    .toMap()
            } else {
                emptyMap()
            }
            val newIds = ArrayList<Long>()
            val ambiguousIds = ArrayList<Long>()
            val pendingMedia = ArrayList<Long>()
            var suppressed = 0
            val storedIds = HashMap<Int, Long>() // decision index -> message id
            var lastStored: MessageCandidate? = null

            for ((index, decision) in reconcile.decisions.withIndex()) {
                val c = decision.candidate
                when (decision) {
                    is Decision.New, is Decision.AmbiguousRepeat -> {
                        if (db.suppressionDao().isSuppressed(suppressionKey, decision.fingerprint, now) > 0) {
                            suppressed++
                            continue
                        }
                        // Checkpoint loss guard: with no previous window (e.g. checkpoint pruned) an
                        // already-stored identical message is linked, not inserted again.
                        if (decision is Decision.New && !decision.confirmedById) {
                            val existingId = preExisting[decision.fingerprint]
                            if (existingId != null) {
                                storedIds[index] = existingId
                                db.observationLinkDao().insert(ObservationLinkEntity(messageId = existingId, eventId = snapshot.eventId, kind = KnownKind.STALE_WINDOW.name, observedAtEpochMs = now))
                                db.messageDao().incrementObservation(existingId)
                                continue
                            }
                        }
                        val dedup = when (decision) {
                            is Decision.AmbiguousRepeat -> DedupState.AMBIGUOUS_REPEAT
                            is Decision.New -> if (decision.confirmedById) DedupState.CONFIRMED else DedupState.CANDIDATE
                            else -> DedupState.CANDIDATE
                        }
                        val wantsMedia = c.media != null
                        val id = db.messageDao().insert(
                            MessageEntity(
                                conversationId = conversationId,
                                sourceMessageId = c.sourceMessageId,
                                senderName = c.sender?.displayName,
                                senderKey = c.sender?.senderKey,
                                isSelf = c.sender?.isSelf ?: false,
                                body = c.body,
                                kind = c.kind.name,
                                sourceTimestampEpochMs = c.sourceTimestampEpochMs,
                                timestampQuality = c.timestampQuality.name,
                                observedAtEpochMs = now,
                                postedAtEpochMs = snapshot.postedAtEpochMs,
                                origin = snapshot.origin.name,
                                contentStatus = c.contentStatus.name,
                                dedupState = dedup.name,
                                revisionCount = 0,
                                observationCount = 1,
                                mediaState = when {
                                    !wantsMedia -> MediaState.NONE.name
                                    !mediaAllowed -> MediaState.DISABLED_BY_USER.name
                                    c.media?.uri == null && c.media?.fromNotificationBitmap != true -> MediaState.PLACEHOLDER_ONLY.name
                                    else -> MediaState.PENDING.name
                                },
                                mediaBlobId = null,
                                mediaUri = c.media?.uri,
                                mediaMimeType = c.media?.mimeType,
                                fingerprint = decision.fingerprint,
                                eventId = snapshot.eventId,
                                sortKey = sortKey(c, snapshot),
                                expiresAtEpochMs = retentionMs?.let { now + it },
                            ),
                        )
                        storedIds[index] = id
                        indexTokens(db, id, c.body)
                        if (decision is Decision.AmbiguousRepeat) {
                            ambiguousIds += id
                            // The linked message may have been deleted since the window was written.
                            decision.existingMessageId?.takeIf { db.messageDao().get(it) != null }?.let {
                                db.observationLinkDao().insert(ObservationLinkEntity(messageId = it, eventId = snapshot.eventId, kind = "AMBIGUOUS_REPEAT", observedAtEpochMs = now))
                            }
                        } else {
                            newIds += id
                        }
                        if (wantsMedia && mediaAllowed && (c.media?.uri != null || c.media?.fromNotificationBitmap == true)) pendingMedia += id
                        lastStored = c
                    }
                    is Decision.Known -> {
                        // Window ids can point at messages deleted by the user or by retention; an
                        // FK violation here would roll back the whole batch, so verify first.
                        val id = decision.existingMessageId?.takeIf { db.messageDao().get(it) != null }
                        if (id != null) {
                            storedIds[index] = id
                            if (decision.kind != KnownKind.REPOST) {
                                db.observationLinkDao().insert(ObservationLinkEntity(messageId = id, eventId = snapshot.eventId, kind = decision.kind.name, observedAtEpochMs = now))
                                db.messageDao().incrementObservation(id)
                            }
                        }
                    }
                    is Decision.Revision -> {
                        val id = decision.existingMessageId
                        val old = db.messageDao().get(id)
                        if (old != null) {
                            db.revisionDao().insert(MessageRevisionEntity(messageId = id, body = old.body, observedAtEpochMs = now, eventId = snapshot.eventId))
                            db.messageDao().applyRevision(id, c.body, snapshot.eventId)
                            db.searchDao().deleteForMessage(id)
                            indexTokens(db, id, c.body)
                            storedIds[index] = id
                        }
                    }
                }
            }

            // Checkpoint with real ids so later windows can link back. Items carried over from the
            // previous window keep their ids; items from this batch map through their decision index.
            val items = reconcile.newWindow.items.map { item ->
                WindowItemJson(item.fingerprint, item.sourceMessageId, item.decisionIndex?.let { storedIds[it] } ?: item.messageId)
            }
            db.checkpointDao().upsert(
                CheckpointEntity(
                    streamKey = identity.streamKey,
                    packageName = identity.scope.packageName,
                    notificationKey = snapshot.notificationKey,
                    windowJson = json.encodeToString(windowSerializer, items),
                    closed = false,
                    parserId = batch.parserId,
                    parserVersion = batch.parserVersion,
                    generation = generation,
                    updatedAtEpochMs = now,
                    postedAtEpochMs = reconcile.newWindow.postedAtEpochMs,
                ),
            )

            // Conversation projection.
            val current = convDao.get(conversationId)
            if (current != null) {
                val touched = newIds.isNotEmpty() || ambiguousIds.isNotEmpty()
                convDao.update(
                    current.copy(
                        title = identity.displayTitle ?: current.title,
                        isGroup = batch.conversation?.isGroup ?: current.isGroup,
                        identityConfidence = identity.confidence.name,
                        lastActivityEpochMs = if (touched) maxOf(current.lastActivityEpochMs, now) else current.lastActivityEpochMs,
                        messageCount = current.messageCount + newIds.size,
                        ambiguousCount = current.ambiguousCount + ambiguousIds.size,
                        lastMessagePreview = if (touched) lastStored?.body?.take(200) ?: current.lastMessagePreview else current.lastMessagePreview,
                        lastSenderName = if (touched) lastStored?.sender?.displayName ?: current.lastSenderName else current.lastSenderName,
                    ),
                )
            }

            db.journalDao().setState(snapshot.eventId, "COMMITTED", null)
            CommitOutcome(conversationId, newIds, ambiguousIds, pendingMedia, suppressed, summaryRecorded)
        }
    }

    private suspend fun indexTokens(db: dev.quietinbox.platform.storage.db.QuietInboxDatabase, messageId: Long, body: String) {
        val tokens = SearchNormalizer.tokens(SearchNormalizer.normalize(body))
        if (tokens.isEmpty()) return
        db.searchDao().insertTokens(tokens.map { SearchTokenEntity(it, messageId) })
    }

    private fun sortKey(c: MessageCandidate, snapshot: NotificationSnapshot): Long = when {
        c.sourceTimestampEpochMs != null && c.timestampQuality != TimestampQuality.OBSERVED_ONLY -> c.sourceTimestampEpochMs!!
        snapshot.postedAtEpochMs != null -> snapshot.postedAtEpochMs!!
        else -> snapshot.observedAtEpochMs
    }
}
