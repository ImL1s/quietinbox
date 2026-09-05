package dev.quietinbox.platform.storage.repo

import dev.quietinbox.core.analytics.ObservedMessage
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.platform.storage.db.DatabaseHolder
import javax.inject.Inject
import javax.inject.Singleton

data class ConversationLabel(val id: Long, val title: String?, val packageName: String)

@Singleton
class AnalyticsRepository @Inject constructor(
    private val holder: DatabaseHolder,
) {
    suspend fun messagesBetween(since: Long, until: Long): List<ObservedMessage> =
        holder.db().messageDao().statsBetween(since, until).map {
            ObservedMessage(
                conversationId = it.conversationId,
                packageName = it.packageName,
                timestampEpochMs = it.sortKey,
                dedupState = it.dedupState.toEnumOr(DedupState.CANDIDATE),
                contentStatus = it.contentStatus.toEnumOr(ContentStatus.UNKNOWN_FORMAT),
                body = it.body,
                senderName = it.senderName,
                isSelf = it.isSelf,
            )
        }

    suspend fun labels(ids: Collection<Long>): Map<Long, ConversationLabel> {
        val dao = holder.db().conversationDao()
        return ids.distinct().mapNotNull { id -> dao.get(id)?.let { ConversationLabel(it.id, it.title, it.packageName) } }.associateBy { it.id }
    }

    suspend fun summaryCountSince(since: Long): Int = holder.db().healthDao().summaryCountSince(since)

    suspend fun earliestTimestamp(): Long? = holder.db().messageDao().earliestSortKey()
}
