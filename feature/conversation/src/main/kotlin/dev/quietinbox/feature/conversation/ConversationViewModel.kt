package dev.quietinbox.feature.conversation

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.Message
import dev.quietinbox.platform.media.MediaCopier
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUiState(
    val loading: Boolean = true,
    val conversation: Conversation? = null,
    val messages: ImmutableList<Message> = persistentListOf(),
    val selection: Set<Long> = emptySet(),
    val sourceInstalled: Boolean = false,
    val sourceLabel: String = "",
    /** The vault could not be opened: the conversation cannot be shown, and a blank page would be a lie (QI-VAULT-010). */
    val vaultLocked: Boolean = false,
)

sealed interface OpenSourceResult {
    data class Launch(val intent: Intent, val fallbackToHome: Boolean) : OpenSourceResult
    data object NotInstalled : OpenSourceResult
}

@HiltViewModel(assistedFactory = ConversationViewModel.Factory::class)
class ConversationViewModel @AssistedInject constructor(
    @Assisted val conversationId: Long,
    @ApplicationContext private val context: Context,
    private val inbox: InboxRepository,
    private val media: MediaCopier,
    private val vault: VaultRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(conversationId: Long): ConversationViewModel
    }

    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    /** A vault read that has not produced anything yet (the vault is opening or locked) versus one that has. */
    private class Loaded<T>(val value: T)

    private val conversation: Flow<Loaded<Conversation?>?> = inbox.observeConversation(conversationId)
        .map<Conversation?, Loaded<Conversation?>?> { Loaded(it) }.catch { emit(Loaded(null)) }.onStart { emit(null) }
    private val messages: Flow<Loaded<List<Message>>?> = inbox.observeMessages(conversationId)
        .map<List<Message>, Loaded<List<Message>>?> { Loaded(it) }.catch { emit(Loaded(emptyList())) }.onStart { emit(null) }

    val state: StateFlow<ConversationUiState> = combine(
        conversation,
        messages,
        selection,
        vault.state,
    ) { c, m, sel, v ->
        val row = c?.value
        val pkg = row?.scope?.packageName
        val installed = pkg != null && context.packageManager.getLaunchIntentForPackage(pkg) != null
        val label = pkg?.let { p ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        }.orEmpty()
        ConversationUiState(
            // Loading until the vault is ready *and* both reads have delivered (no flash of "empty").
            loading = v !is VaultState.Locked && (v is VaultState.Opening || c == null || m == null),
            conversation = row,
            messages = m?.value.orEmpty().toImmutableList(),
            selection = sel,
            sourceInstalled = installed,
            sourceLabel = label,
            vaultLocked = v is VaultState.Locked,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    fun retryVault() = viewModelScope.launch { runCatching { vault.retryOpen() } }

    init {
        viewModelScope.launch { runCatching { inbox.markViewed(conversationId, System.currentTimeMillis()) } }
    }

    fun markViewed() = viewModelScope.launch { runCatching { inbox.markViewed(conversationId, System.currentTimeMillis()) } }

    fun toggleSelect(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun clearSelection() = selection.update { emptySet() }

    fun setPinned(pinned: Boolean) = viewModelScope.launch { runCatching { inbox.setPinned(conversationId, pinned) } }
    fun setArchived(archived: Boolean) = viewModelScope.launch { runCatching { inbox.setArchived(conversationId, archived) } }

    fun deleteSelected() = viewModelScope.launch {
        val ids = selection.value.toList()
        selection.value = emptySet()
        runCatching { inbox.deleteMessages(ids, System.currentTimeMillis(), SUPPRESSION_TTL_MS) }
    }

    fun deleteConversation(onDone: () -> Unit) = viewModelScope.launch {
        val ok = runCatching { inbox.deleteConversation(conversationId, System.currentTimeMillis(), SUPPRESSION_TTL_MS) }.isSuccess
        if (ok) onDone()
    }

    /**
     * Explicit user action only. QuietInbox never persists source `PendingIntent`s, so the source
     * app's launcher activity is used; this may mark the chat as read on the source side.
     */
    fun openSourceIntent(): OpenSourceResult {
        val pkg = state.value.conversation?.scope?.packageName ?: return OpenSourceResult.NotInstalled
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return OpenSourceResult.NotInstalled
        return OpenSourceResult.Launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fallbackToHome = true)
    }

    suspend fun loadThumbnail(blobId: Long): ByteArray? = media.load(blobId, thumbnail = true)

    companion object {
        const val SUPPRESSION_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
