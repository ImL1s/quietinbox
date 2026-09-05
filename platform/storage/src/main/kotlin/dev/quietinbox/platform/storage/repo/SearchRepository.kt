package dev.quietinbox.platform.storage.repo

import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.SearchNormalizer
import dev.quietinbox.platform.storage.db.DatabaseHolder
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHit(val message: Message, val conversationTitle: String?, val packageName: String)

/**
 * Parameterised, paged search over the encrypted n-gram index. Query text never reaches SQL
 * syntax: it is tokenised with the same function that built the index, and every candidate is
 * re-verified as a real substring of the normalised body (plan section 8).
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
        offset: Int = 0,
    ): List<SearchHit> {
        val normalized = SearchNormalizer.normalize(query)
        if (normalized.isBlank()) return emptyList()
        val tokens = SearchNormalizer.tokens(normalized).toList()
        if (tokens.isEmpty()) return emptyList()
        val db = holder.db()
        val rows = db.searchDao().search(tokens, tokens.size, packages.isEmpty(), packages.toList(), fromMs, toMs, limit, offset)
        val verified = rows.filter { SearchNormalizer.normalize(it.body).contains(normalized) }
        val conversations = verified.map { it.conversationId }.distinct().associateWith { db.conversationDao().get(it) }
        return verified.map { row ->
            val c = conversations[row.conversationId]
            SearchHit(row.toDomain(), c?.title, c?.packageName ?: "")
        }
    }
}
