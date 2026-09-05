package dev.quietinbox.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.platform.backup.BackupResult
import dev.quietinbox.platform.backup.BackupService
import dev.quietinbox.platform.crypto.KeyResult
import dev.quietinbox.platform.storage.repo.VaultRepository
import dev.quietinbox.platform.storage.settings.AppSettings
import dev.quietinbox.platform.storage.settings.SettingsRepository
import dev.quietinbox.platform.storage.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val versionName: String = "",
    val recoveryKey: String? = null,
    val busy: Boolean = false,
    val lastBackup: BackupResult? = null,
)

/** Hooks the app module implements for reminder scheduling (kept out of the feature module). */
interface ReminderScheduling {
    fun reschedule()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val backup: BackupService,
    private val vault: VaultRepository,
    private val reminders: ReminderScheduling,
) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState(versionName = versionName()))

    val state: StateFlow<SettingsUiState> = combine(settings.settings, local) { s, l -> l.copy(settings = s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { settings.setDynamicColor(v) }
    fun setReduceMotion(v: Boolean) = viewModelScope.launch { settings.setReduceMotion(v) }
    fun setUiLock(v: Boolean) = viewModelScope.launch { settings.setUiLock(v) }
    fun setScreenshotProtection(v: Boolean) = viewModelScope.launch { settings.setScreenshotProtection(v) }
    fun setRetentionDays(d: Int) = viewModelScope.launch { settings.setRetentionDays(d) }
    fun setJournalTtl(h: Int) = viewModelScope.launch { settings.setJournalTtlHours(h) }
    fun setMediaCopy(v: Boolean) = viewModelScope.launch { settings.setMediaCopy(v) }
    fun acceptMediaDisclosure() = viewModelScope.launch { settings.setMediaDisclosureAccepted(true) }
    fun setReminders(v: Boolean) = viewModelScope.launch { settings.setReminders(v); reminders.reschedule() }
    fun setReminderTime(h: Int, m: Int) = viewModelScope.launch { settings.setReminderTime(h, m); reminders.reschedule() }
    fun toggleReminderDay(day: Int) = viewModelScope.launch {
        val days = state.value.settings.reminderWeekdays
        settings.setReminderWeekdays(if (day in days) days - day else days + day)
        reminders.reschedule()
    }

    fun showRecoveryKey() = viewModelScope.launch(Dispatchers.IO) {
        val text = (backup.recoveryKeyText() as? KeyResult.Ok)?.value ?: ""
        local.update { it.copy(recoveryKey = text) }
    }

    fun hideRecoveryKey() = local.update { it.copy(recoveryKey = null) }

    fun acknowledgeRecoveryKey() = viewModelScope.launch { settings.setRecoveryKeyAcknowledged(true) }

    fun export(target: Uri) = viewModelScope.launch {
        local.update { it.copy(busy = true, lastBackup = null) }
        val result = backup.export(target, state.value.versionName)
        local.update { it.copy(busy = false, lastBackup = result) }
    }

    fun import(source: Uri, key: String) = viewModelScope.launch {
        local.update { it.copy(busy = true, lastBackup = null) }
        val result = backup.import(source, key)
        local.update { it.copy(busy = false, lastBackup = result) }
    }

    fun clearBackupResult() = local.update { it.copy(lastBackup = null) }

    fun deleteEverything(onDone: () -> Unit) = viewModelScope.launch {
        local.update { it.copy(busy = true) }
        runCatching { vault.deleteEverything() }
        local.update { it.copy(busy = false) }
        onDone()
    }
}
