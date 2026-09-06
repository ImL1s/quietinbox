package dev.quietinbox.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SearchHit
import dev.quietinbox.platform.storage.repo.SearchRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
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
    /** The vault could not be opened: nothing can be searched and "no results" would be a lie (QI-VAULT-010). */
    val vaultLocked: Boolean = false,
    /** The vault is still opening; a query typed now runs once it is ready. */
    val vaultOpening: Boolean = true,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    inbox: InboxRepository,
    private val vault: VaultRepository,
) : ViewModel() {
    private val local = MutableStateFlow(SearchUiState())

    val state: StateFlow<SearchUiState> = combine(local, inbox.observePackagesWithData().catch { emit(emptyList()) }, vault.state) { s, p, v ->
        s.copy(availablePackages = p.toImmutableList(), vaultLocked = v is VaultState.Locked, vaultOpening = v is VaultState.Opening)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch {
            // A query is re-run when the vault becomes ready (typed while opening, or after a retry).
            combine(local.debounce(250), vault.state) { s, v -> s to v }
                .distinctUntilChanged { (a, va), (b, vb) -> a.query == b.query && a.range == b.range && a.packages == b.packages && va::class == vb::class }
                .collect { (s, v) -> if (v is VaultState.Ready) run(s) else if (v is VaultState.Locked) local.update { it.copy(results = persistentListOf(), searching = false, searched = false) } }
        }
    }

    fun retryVault() = viewModelScope.launch { runCatching { vault.retryOpen() } }

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
