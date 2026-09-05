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
import dev.quietinbox.platform.storage.repo.InboxRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(conversationId: Long): ConversationViewModel
    }

    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<ConversationUiState> = combine(
        inbox.observeConversation(conversationId).catch { emit(null) },
        inbox.observeMessages(conversationId).catch { emit(emptyList()) },
        selection,
    ) { c, m, sel ->
        val pkg = c?.scope?.packageName
        val installed = pkg != null && context.packageManager.getLaunchIntentForPackage(pkg) != null
        val label = pkg?.let { p ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        }.orEmpty()
        ConversationUiState(
            loading = false,
            conversation = c,
            messages = m.toImmutableList(),
            selection = sel,
            sourceInstalled = installed,
            sourceLabel = label,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

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
