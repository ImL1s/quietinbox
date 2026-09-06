package dev.quietinbox.platform.storage.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "quietinbox_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Non-sensitive preferences. Anything secret goes into the encrypted vault, never here. */
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val retentionDays: Int = 30,
    val journalTtlHours: Int = 24,
    val uiLockEnabled: Boolean = false,
    val screenshotProtection: Boolean = true,
    val dynamicColor: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Effective value: media is copied only when the switch is on **and** the disclosure was
     * accepted (QI-PRIV-002). Off on a fresh install; an existing install that had the old
     * default but never saw the disclosure reads as off too.
     */
    val mediaCopyEnabled: Boolean = false,
    val mediaDisclosureAccepted: Boolean = false,
    val remindersEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val reminderWeekdays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val reminderPackages: Set<String> = emptySet(),
    val captureActiveOnConnect: Boolean = true,
    val recoveryKeyAcknowledged: Boolean = false,
    val reduceMotion: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_completed")
        val retentionDays = intPreferencesKey("retention_days")
        val journalTtl = intPreferencesKey("journal_ttl_hours")
        val uiLock = booleanPreferencesKey("ui_lock")
        val screenshot = booleanPreferencesKey("screenshot_protection")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val theme = stringPreferencesKey("theme_mode")
        val mediaCopy = booleanPreferencesKey("media_copy")
        val mediaDisclosure = booleanPreferencesKey("media_disclosure")
        val reminders = booleanPreferencesKey("reminders_enabled")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val reminderDays = stringSetPreferencesKey("reminder_days")
        val reminderPackages = stringSetPreferencesKey("reminder_packages")
        val activeOnConnect = booleanPreferencesKey("active_on_connect")
        val recoveryAck = booleanPreferencesKey("recovery_ack")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            onboardingCompleted = p[Keys.onboarding] ?: false,
            retentionDays = p[Keys.retentionDays] ?: 30,
            journalTtlHours = p[Keys.journalTtl] ?: 24,
            uiLockEnabled = p[Keys.uiLock] ?: false,
            screenshotProtection = p[Keys.screenshot] ?: true,
            dynamicColor = p[Keys.dynamicColor] ?: false,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            mediaCopyEnabled = (p[Keys.mediaCopy] ?: false) && (p[Keys.mediaDisclosure] ?: false),
            mediaDisclosureAccepted = p[Keys.mediaDisclosure] ?: false,
            remindersEnabled = p[Keys.reminders] ?: false,
            reminderHour = p[Keys.reminderHour] ?: 20,
            reminderMinute = p[Keys.reminderMinute] ?: 0,
            reminderWeekdays = p[Keys.reminderDays]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(1, 2, 3, 4, 5, 6, 7),
            reminderPackages = p[Keys.reminderPackages] ?: emptySet(),
            captureActiveOnConnect = p[Keys.activeOnConnect] ?: true,
            recoveryKeyAcknowledged = p[Keys.recoveryAck] ?: false,
            reduceMotion = p[Keys.reduceMotion] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingCompleted(value: Boolean) = edit { it[Keys.onboarding] = value }
    suspend fun setRetentionDays(days: Int) = edit { it[Keys.retentionDays] = days.coerceIn(1, 3650) }
    suspend fun setJournalTtlHours(hours: Int) = edit { it[Keys.journalTtl] = hours.coerceIn(1, 168) }
    suspend fun setUiLock(enabled: Boolean) = edit { it[Keys.uiLock] = enabled }
    suspend fun setScreenshotProtection(enabled: Boolean) = edit { it[Keys.screenshot] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.dynamicColor] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }
    suspend fun setMediaCopy(enabled: Boolean) = edit { it[Keys.mediaCopy] = enabled }
    suspend fun setMediaDisclosureAccepted(value: Boolean) = edit { it[Keys.mediaDisclosure] = value }
    suspend fun setReminders(enabled: Boolean) = edit { it[Keys.reminders] = enabled }
    suspend fun setReminderTime(hour: Int, minute: Int) = edit {
        it[Keys.reminderHour] = hour.coerceIn(0, 23)
        it[Keys.reminderMinute] = minute.coerceIn(0, 59)
    }
    suspend fun setReminderWeekdays(days: Set<Int>) = edit { it[Keys.reminderDays] = days.map(Int::toString).toSet() }
    suspend fun setReminderPackages(packages: Set<String>) = edit { it[Keys.reminderPackages] = packages }
    suspend fun setCaptureActiveOnConnect(value: Boolean) = edit { it[Keys.activeOnConnect] = value }
    suspend fun setRecoveryKeyAcknowledged(value: Boolean) = edit { it[Keys.recoveryAck] = value }
    suspend fun setReduceMotion(value: Boolean) = edit { it[Keys.reduceMotion] = value }

    suspend fun clearAll() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }
}
