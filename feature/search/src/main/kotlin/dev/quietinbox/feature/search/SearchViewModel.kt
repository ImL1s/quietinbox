package dev.quietinbox.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SearchHit
import dev.quietinbox.platform.storage.repo.SearchRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchRange { ALL, TODAY, DAYS_7, DAYS_30 }

data class SearchUiState(
    val query: String = "",
    val range: SearchRange = SearchRange.ALL,
    val packages: Set<String> = emptySet(),
    val availablePackages: ImmutableList<String> = persistentListOf(),
    val results: ImmutableList<SearchHit> = persistentListOf(),
    val searching: Boolean = false,
    val searched: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    inbox: InboxRepository,
) : ViewModel() {
    private val local = MutableStateFlow(SearchUiState())

    val state: StateFlow<SearchUiState> = combine(local, inbox.observePackagesWithData().catch { emit(emptyList()) }) { s, p ->
        s.copy(availablePackages = p.toImmutableList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch {
            local.debounce(250).distinctUntilChanged { a, b -> a.query == b.query && a.range == b.range && a.packages == b.packages }
                .collect { s -> run(s) }
        }
    }

    fun setQuery(q: String) = local.update { it.copy(query = q, searching = q.isNotBlank()) }
    fun setRange(r: SearchRange) = local.update { it.copy(range = r) }
    fun togglePackage(p: String) = local.update { it.copy(packages = if (p in it.packages) it.packages - p else it.packages + p) }
    fun clearPackages() = local.update { it.copy(packages = emptySet()) }

    private suspend fun run(s: SearchUiState) {
        if (s.query.isBlank()) {
            local.update { it.copy(results = persistentListOf(), searching = false, searched = false) }
            return
        }
        val now = System.currentTimeMillis()
        val from = when (s.range) {
            SearchRange.ALL -> null
            SearchRange.TODAY -> java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            SearchRange.DAYS_7 -> now - 7L * 24 * 60 * 60 * 1000
            SearchRange.DAYS_30 -> now - 30L * 24 * 60 * 60 * 1000
        }
        val hits = runCatching { search.search(s.query, s.packages, from, null, limit = 100) }.getOrDefault(emptyList())
        local.update { it.copy(results = hits.toImmutableList(), searching = false, searched = true) }
    }
}
