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
    /**
     * Observed messages in the period, oldest first, at most [limit] rows (the newest ones). A page
     * that receives exactly [limit] rows must tell the user the period was capped.
     */
    suspend fun messagesBetween(since: Long, until: Long, limit: Int = MESSAGE_CAP): List<ObservedMessage> =
        holder.db().messageDao().statsBetween(since, until, limit, System.currentTimeMillis()).asReversed().map {
            ObservedMessage(
                conversationId = it.conversationId,
                packageName = it.packageName,
                timestampEpochMs = it.sortKey,
                dedupState = it.dedupState.toEnumOr(DedupState.CANDIDATE),
                contentStatus = it.contentStatus.toEnumOr(ContentStatus.UNKNOWN_FORMAT),
                body = it.body,
                senderName = it.senderName,
                isSelf = it.isSelf,
                senderKey = it.senderKey,
            )
        }

    suspend fun labels(ids: Collection<Long>): Map<Long, ConversationLabel> {
        val dao = holder.db().conversationDao()
        return ids.distinct().mapNotNull { id -> dao.get(id)?.let { ConversationLabel(it.id, it.title, it.packageName) } }.associateBy { it.id }
    }

    suspend fun summaryCountSince(since: Long): Int = holder.db().healthDao().summaryCountSince(since)

    /** Summary-only observations inside a closed period (a past month must not count later summaries). */
    suspend fun summaryCountBetween(since: Long, until: Long): Int = holder.db().healthDao().summaryCountBetween(since, until)

    suspend fun earliestTimestamp(): Long? = holder.db().messageDao().earliestSortKey(System.currentTimeMillis())

    companion object {
        /** Upper bound on messages loaded into memory for one analytics computation. */
        const val MESSAGE_CAP: Int = 50_000
    }
}
