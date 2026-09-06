package dev.quietinbox.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.GapInterval
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.CaptureStatus
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.capture.SyntheticNotifications
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.InboxCounts
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InboxFilter(val packages: Set<String> = emptySet(), val archived: Boolean = false)

data class InboxUiState(
    val loading: Boolean = true,
    val conversations: ImmutableList<Conversation> = persistentListOf(),
    val availablePackages: ImmutableList<String> = persistentListOf(),
    val filter: InboxFilter = InboxFilter(),
    val counts: InboxCounts = InboxCounts(0, 0, 0, 0),
    val capture: CaptureStatus = CaptureStatus(),
    val latestGap: GapInterval? = null,
    val vaultLocked: Boolean = false,
    val listenerGranted: Boolean = false,
    val testSent: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val inbox: InboxRepository,
    private val health: HealthRepository,
    private val coordinator: CaptureCoordinator,
    private val synthetic: SyntheticNotifications,
    private val listenerAccess: ListenerAccess,
    vault: VaultRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(InboxFilter())
    private val testSent = MutableStateFlow(false)

    private val conversations = filter.flatMapLatest { f ->
        inbox.observeConversations(f.archived, f.packages).catch { emit(emptyList()) }
    }

    val state: StateFlow<InboxUiState> = combine(
        combine(conversations, inbox.observePackagesWithData().catch { emit(emptyList()) }, filter) { c, p, f -> Triple(c, p, f) },
        combine(inbox.observeCounts().catch { emit(InboxCounts(0, 0, 0, 0)) }, health.observeGaps(1).catch { emit(emptyList()) }) { c, g -> c to g.firstOrNull() },
        coordinator.status,
        vault.state,
        testSent,
    ) { (c, p, f), (counts, gap), capture, vaultState, sent ->
        InboxUiState(
            loading = false,
            conversations = c.toImmutableList(),
            availablePackages = p.toImmutableList(),
            filter = f,
            counts = counts,
            capture = capture,
            latestGap = gap,
            vaultLocked = vaultState is VaultState.Locked,
            listenerGranted = listenerAccess.isGranted(),
            testSent = sent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun togglePackage(packageName: String) = filter.update { f ->
        f.copy(packages = if (packageName in f.packages) f.packages - packageName else f.packages + packageName)
    }

    fun clearPackages() = filter.update { it.copy(packages = emptySet()) }

    fun setArchived(archived: Boolean) = filter.update { it.copy(archived = archived) }

    fun setPinned(id: Long, pinned: Boolean) = viewModelScope.launch { runCatching { inbox.setPinned(id, pinned) } }

    fun setArchivedConversation(id: Long, archived: Boolean) = viewModelScope.launch { runCatching { inbox.setArchived(id, archived) } }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        runCatching { inbox.deleteConversation(id, System.currentTimeMillis(), SUPPRESSION_TTL_MS) }
    }

    fun sendTestNotification() {
        synthetic.postConversation(count = 3, iconRes = R.drawable.ic_stat_quiet)
        testSent.value = true
    }

    fun refreshPermission() = coordinator.refreshPermissionState()

    fun canPostNotifications(): Boolean = synthetic.canPost()

    fun listenerSettingsIntent() = listenerAccess.settingsIntent()

    /** False when no settings screen exists on this device; the screen then shows the manual path. */
    fun openListenerSettings(from: android.content.Context): Boolean = listenerAccess.openSettings(from)

    companion object {
        const val SUPPRESSION_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
