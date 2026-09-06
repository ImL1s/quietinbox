package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.MessageRevision
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DeletionSuppressionEntity
import dev.quietinbox.platform.storage.db.MediaBlobEntity
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.retention.MediaDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class InboxCounts(val conversations: Int, val messages: Int, val ambiguous: Int, val summaries: Int)

/** Read side for the inbox and conversation screens; local-only state changes. */
@Singleton
class InboxRepository @Inject constructor(
    private val holder: DatabaseHolder,
    private val mediaDir: MediaDirectory,
) {
    val vaultState: Flow<VaultState> get() = holder.state

    fun observeConversations(archived: Boolean, packages: Set<String>): Flow<List<Conversation>> =
        holder.flowWithDb { db ->
            db.conversationDao().observeInbox(archived, packages.isEmpty(), packages.toList())
        }.map { rows -> rows.map { it.toDomain() } }

    fun observeConversation(id: Long): Flow<Conversation?> =
        holder.flowWithDb { db -> db.conversationDao().observe(id) }.map { it?.toDomain() }

    /** Expired copies are hidden from the moment of collection, not only once retention ran (QI-DATA-007). */
    fun observeMessages(conversationId: Long, now: Long = System.currentTimeMillis()): Flow<List<Message>> =
        holder.flowWithDb { db -> db.messageDao().observeForConversation(conversationId, now) }.map { rows -> rows.map { it.toDomain() } }

    fun observeRevisions(messageId: Long): Flow<List<MessageRevision>> =
        holder.flowWithDb { db -> db.revisionDao().observeForMessage(messageId) }.map { rows -> rows.map { it.toDomain() } }

    fun observePackagesWithData(): Flow<List<String>> = holder.flowWithDb { db -> db.conversationDao().observePackages() }

    fun observeCounts(now: Long = System.currentTimeMillis()): Flow<InboxCounts> = holder.flowWithDb { db ->
        combine(
            db.conversationDao().observeCount(),
            db.messageDao().observeCount(now),
            db.messageDao().observeAmbiguousCount(now),
            db.healthDao().observeSummaryCount(),
        ) { c, m, a, s -> InboxCounts(c, m, a, s) }
    }

    suspend fun markViewed(conversationId: Long, now: Long) = holder.db().conversationDao().markViewed(conversationId, now)
    suspend fun setPinned(conversationId: Long, pinned: Boolean) = holder.db().conversationDao().setPinned(conversationId, pinned)
    suspend fun setArchived(conversationId: Long, archived: Boolean) = holder.db().conversationDao().setArchived(conversationId, archived)

    /**
     * Deletes messages and records a body-free suppression token so an active-notification
     * replay cannot resurrect them (plan section 7.3). The token carries the deleted message's
     * source id and post time so a later, genuinely new message with the same text is not
     * swallowed (QI-DEDUP-009). Media rows go in the same transaction and their files right
     * after it; the conversation projection is rebuilt from what remains (QI-DATA-004).
     */
    suspend fun deleteMessages(ids: List<Long>, now: Long, suppressionTtlMs: Long) {
        if (ids.isEmpty()) return
        val db = holder.db()
        val files = db.withTransaction {
            val rows = db.messageDao().getAll(ids)
            val scopeKeys = HashMap<Long, String>()
            for (row in rows) {
                val key = scopeKeys.getOrPut(row.conversationId) { db.conversationDao().get(row.conversationId)?.suppressionScopeKey() ?: "" }
                if (key.isNotEmpty()) db.suppressionDao().upsert(DeletionSuppressionEntity(key, row.fingerprint, now + suppressionTtlMs, row.sourceMessageId, row.postedAtEpochMs))
            }
            val blobs = db.mediaDao().forMessages(ids)
            if (blobs.isNotEmpty()) db.mediaDao().delete(blobs.map { it.id })
            db.messageDao().delete(ids)
            db.conversationDao().rebuildProjection(rows.map { it.conversationId }.distinct(), now)
            blobs.fileNames()
        }
        for (f in files) mediaDir.delete(f)
    }

    /** Deletes a conversation and every message in it, with the same replay suppression. */
    suspend fun deleteConversation(conversationId: Long, now: Long, suppressionTtlMs: Long) {
        val db = holder.db()
        val files = db.withTransaction {
            val key = db.conversationDao().get(conversationId)?.suppressionScopeKey()
            if (key != null) {
                for (row in db.messageDao().forConversation(conversationId)) {
                    db.suppressionDao().upsert(DeletionSuppressionEntity(key, row.fingerprint, now + suppressionTtlMs, row.sourceMessageId, row.postedAtEpochMs))
                }
            }
            val blobs = db.mediaDao().forConversation(conversationId)
            if (blobs.isNotEmpty()) db.mediaDao().delete(blobs.map { it.id })
            db.conversationDao().delete(conversationId)
            blobs.fileNames()
        }
        for (f in files) mediaDir.delete(f)
    }
}

/** Every file a set of blob rows owns: the blob and, when present, its thumbnail. */
internal fun List<MediaBlobEntity>.fileNames(): List<String> = flatMap { listOfNotNull(it.fileName, it.thumbFileName) }

/** Stable identity of a conversation for suppression: scope + identity key, independent of the row id. */
internal fun ConversationEntity.suppressionScopeKey(): String =
    suppressionScopeKey(SourceScope(packageName, profileKey, accountKey), identityKey)

internal fun suppressionScopeKey(scope: SourceScope, identityKey: String): String = scope.key + "#" + identityKey
