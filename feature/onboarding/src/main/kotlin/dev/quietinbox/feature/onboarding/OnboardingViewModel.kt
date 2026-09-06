package dev.quietinbox.feature.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.parser.ParserRegistry
import dev.quietinbox.parsers.apps.AppParsers
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.capture.SyntheticNotifications
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceChoice(val packageName: String, val label: String, val installed: Boolean, val hasAdapter: Boolean)

data class OnboardingUiState(
    val step: Int = 0,
    val choices: List<SourceChoice> = emptyList(),
    val selected: Set<String> = emptySet(),
    val granted: Boolean = false,
    val testSent: Boolean = false,
    val capturedMessages: Int = 0,
    val canPostNotifications: Boolean = true,
) {
    val stepCount: Int get() = 5
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val sources: SourceRepository,
    private val listenerAccess: ListenerAccess,
    private val synthetic: SyntheticNotifications,
    private val coordinator: CaptureCoordinator,
    inbox: InboxRepository,
) : ViewModel() {
    private val registry = ParserRegistry(AppParsers.all())
    private val local = MutableStateFlow(OnboardingUiState(choices = buildChoices(), selected = defaultSelection()))

    val state: StateFlow<OnboardingUiState> = combine(local, inbox.observeCounts().catch { }) { s, counts ->
        s.copy(capturedMessages = counts.messages, granted = listenerAccess.isGranted(), canPostNotifications = synthetic.canPost())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    private fun buildChoices(): List<SourceChoice> {
        val pm = context.packageManager
        return KnownSources.ALL.map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            SourceChoice(pkg, label ?: prettyName(pkg), installed = label != null, hasAdapter = registry.adapterFor(pkg) != null)
        }
    }

    private fun defaultSelection(): Set<String> = buildChoices().filter { it.installed }.map { it.packageName }.toSet()

    private fun prettyName(pkg: String) = when (pkg) {
        KnownSources.LINE -> "LINE"
        KnownSources.WHATSAPP -> "WhatsApp"
        KnownSources.TELEGRAM -> "Telegram"
        KnownSources.INSTAGRAM -> "Instagram"
        KnownSources.MESSENGER -> "Messenger"
        else -> pkg
    }

    fun next() = local.update { it.copy(step = (it.step + 1).coerceAtMost(it.stepCount - 1)) }
    fun back() = local.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun toggle(pkg: String) = local.update { s -> s.copy(selected = if (pkg in s.selected) s.selected - pkg else s.selected + pkg) }

    fun refreshPermission() {
        coordinator.refreshPermissionState()
        local.update { it.copy(granted = listenerAccess.isGranted()) }
    }

    /** False when no settings screen exists on this device; the screen then shows the manual path. */
    fun openListenerSettings(from: Context): Boolean = listenerAccess.openSettings(from)

    fun sendTest() {
        synthetic.postConversation(count = 3, iconRes = R.drawable.ic_stat_quiet)
        local.update { it.copy(testSent = true) }
    }

    /** Persists the chosen sources first so a test/real notification is accepted immediately. */
    fun persistSources() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        for (choice in local.value.choices) {
            if (choice.packageName in local.value.selected) {
                runCatching { coordinator.addSource(choice.packageName, choice.label, registry.adapterFor(choice.packageName)?.id, now) }
            }
        }
    }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        persistSources().join()
        settings.setOnboardingCompleted(true)
        onDone()
    }
}
