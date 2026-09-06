package dev.quietinbox.platform.storage.repo

import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.SearchNormalizer
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.MessageEntity
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHit(val message: Message, val conversationTitle: String?, val packageName: String)

/** Keyset position after the last candidate examined (newest first): resume with it, never with an offset. */
data class SearchCursor(val sortKey: Long, val id: Long)

/**
 * One page of verified hits; [next] is null when the index has no more candidates. The search
 * screen currently shows the first page only (100 hits) and does not continue with the cursor.
 */
data class SearchPage(val hits: List<SearchHit>, val next: SearchCursor?)

/**
 * Parameterised, paged search over the encrypted n-gram index. Query text never reaches SQL
 * syntax: it is tokenised with the same function that built the index, and every candidate is
 * re-verified as a real substring of the normalised body (plan section 8).
 *
 * Candidates are pulled in keyset pages (sortKey, id) and the loop continues until [limit]
 * verified hits are collected or the index is exhausted, so a run of false-positive candidates
 * (every query token present, the substring absent) can neither under-fill a page nor hide a
 * later true hit; a keyset cursor does not drift when new messages are inserted (QI-SEARCH-011).
 */
@Singleton
class SearchRepository @Inject constructor(
    private val holder: DatabaseHolder,
) {
    suspend fun search(
        query: String,
        packages: Set<String> = emptySet(),
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int = 50,
    ): List<SearchHit> = searchPage(query, packages, fromMs, toMs, limit, cursor = null).hits

    suspend fun searchPage(
        query: String,
        packages: Set<String> = emptySet(),
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int = 50,
        cursor: SearchCursor? = null,
        now: Long = System.currentTimeMillis(),
    ): SearchPage {
        val normalized = SearchNormalizer.normalize(query)
        if (normalized.isBlank()) return SearchPage(emptyList(), null)
        val tokens = SearchNormalizer.queryTokens(normalized).toList()
        if (tokens.isEmpty()) return SearchPage(emptyList(), null)
        val db = holder.db()
        val pageSize = maxOf(limit, CANDIDATE_PAGE)
        var position = cursor ?: SearchCursor(Long.MAX_VALUE, Long.MAX_VALUE)
        val verified = ArrayList<MessageEntity>(limit)
        var exhausted = false
        var pages = 0
        while (verified.size < limit && !exhausted && pages++ < MAX_CANDIDATE_PAGES) {
            val rows = db.searchDao().searchCandidates(tokens, tokens.size, packages.isEmpty(), packages.toList(), fromMs, toMs, position.sortKey, position.id, pageSize, now)
            if (rows.isEmpty()) {
                exhausted = true
                break
            }
            for (row in rows) {
                if (verified.size == limit) break
                position = SearchCursor(row.sortKey, row.id)
                if (SearchNormalizer.normalize(row.body).contains(normalized)) verified += row
            }
            if (rows.size < pageSize && verified.size < limit) exhausted = true
        }
        val conversations = verified.map { it.conversationId }.distinct().associateWith { db.conversationDao().get(it) }
        val hits = verified.map { row ->
            val c = conversations[row.conversationId]
            SearchHit(row.toDomain(), c?.title, c?.packageName ?: "")
        }
        return SearchPage(hits, if (exhausted) null else position)
    }

    private companion object {
        /** Candidate rows fetched per keyset page. */
        const val CANDIDATE_PAGE = 200

        /** Upper bound on candidate pages per call (40,000 candidates); the cursor lets the caller continue. */
        const val MAX_CANDIDATE_PAGES = 200
    }
}
