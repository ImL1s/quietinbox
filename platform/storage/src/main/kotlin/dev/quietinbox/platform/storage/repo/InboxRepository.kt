package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.MessageRevision
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DeletionSuppressionEntity
import dev.quietinbox.platform.storage.db.VaultState
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
) {
    val vaultState: Flow<VaultState> get() = holder.state

    fun observeConversations(archived: Boolean, packages: Set<String>): Flow<List<Conversation>> =
        holder.flowWithDb { db ->
            db.conversationDao().observeInbox(archived, packages.isEmpty(), packages.toList())
        }.map { rows -> rows.map { it.toDomain() } }

    fun observeConversation(id: Long): Flow<Conversation?> =
        holder.flowWithDb { db -> db.conversationDao().observe(id) }.map { it?.toDomain() }

    fun observeMessages(conversationId: Long): Flow<List<Message>> =
        holder.flowWithDb { db -> db.messageDao().observeForConversation(conversationId) }.map { rows -> rows.map { it.toDomain() } }

    fun observeRevisions(messageId: Long): Flow<List<MessageRevision>> =
        holder.flowWithDb { db -> db.revisionDao().observeForMessage(messageId) }.map { rows -> rows.map { it.toDomain() } }

    fun observePackagesWithData(): Flow<List<String>> = holder.flowWithDb { db -> db.conversationDao().observePackages() }

    fun observeCounts(): Flow<InboxCounts> = holder.flowWithDb { db ->
        combine(
            db.conversationDao().observeCount(),
            db.messageDao().observeCount(),
            db.messageDao().observeAmbiguousCount(),
            db.healthDao().observeSummaryCount(),
        ) { c, m, a, s -> InboxCounts(c, m, a, s) }
    }

    suspend fun markViewed(conversationId: Long, now: Long) = holder.db().conversationDao().markViewed(conversationId, now)
    suspend fun setPinned(conversationId: Long, pinned: Boolean) = holder.db().conversationDao().setPinned(conversationId, pinned)
    suspend fun setArchived(conversationId: Long, archived: Boolean) = holder.db().conversationDao().setArchived(conversationId, archived)

    /**
     * Deletes messages and records a body-free suppression token so an active-notification
     * replay cannot resurrect them (plan section 7.3). Media files are removed by retention.
     */
    suspend fun deleteMessages(ids: List<Long>, now: Long, suppressionTtlMs: Long) {
        if (ids.isEmpty()) return
        val db = holder.db()
        db.withTransaction {
            val rows = db.messageDao().getAll(ids)
            val scopeKeys = HashMap<Long, String>()
            for (row in rows) {
                val key = scopeKeys.getOrPut(row.conversationId) { db.conversationDao().get(row.conversationId)?.suppressionScopeKey() ?: "" }
                if (key.isNotEmpty()) db.suppressionDao().upsert(DeletionSuppressionEntity(key, row.fingerprint, now + suppressionTtlMs))
            }
            db.messageDao().delete(ids)
            val byConversation = rows.groupBy { it.conversationId }
            for ((cid, deleted) in byConversation) {
                val c = db.conversationDao().get(cid) ?: continue
                val ambiguousDeleted = deleted.count { it.dedupState == "AMBIGUOUS_REPEAT" }
                db.conversationDao().update(
                    c.copy(
                        messageCount = (c.messageCount - (deleted.size - ambiguousDeleted)).coerceAtLeast(0),
                        ambiguousCount = (c.ambiguousCount - ambiguousDeleted).coerceAtLeast(0),
                    ),
                )
            }
        }
    }

    /** Deletes a conversation and every message in it, with the same replay suppression. */
    suspend fun deleteConversation(conversationId: Long, now: Long, suppressionTtlMs: Long) {
        val db = holder.db()
        db.withTransaction {
            val key = db.conversationDao().get(conversationId)?.suppressionScopeKey()
            if (key != null) {
                for (row in db.messageDao().forConversation(conversationId)) {
                    db.suppressionDao().upsert(DeletionSuppressionEntity(key, row.fingerprint, now + suppressionTtlMs))
                }
            }
            db.conversationDao().delete(conversationId)
        }
    }
}

/** Stable identity of a conversation for suppression: scope + identity key, independent of the row id. */
internal fun ConversationEntity.suppressionScopeKey(): String =
    suppressionScopeKey(SourceScope(packageName, profileKey, accountKey), identityKey)

internal fun suppressionScopeKey(scope: SourceScope, identityKey: String): String = scope.key + "#" + identityKey
