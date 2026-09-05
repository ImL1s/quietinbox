package dev.quietinbox.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI gate only (plan section 9): when the user enables "lock the app", content is hidden until
 * the device credential or a biometric succeeds. Capture and encryption are unaffected.
 */
@Singleton
class LockController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    @Volatile
    private var enabled = false

    @Volatile
    private var backgroundedAt: Long = 0L

    init {
        scope.launch {
            settings.settings.collect { s ->
                val was = enabled
                enabled = s.uiLockEnabled
                if (!enabled) _locked.value = false
                if (!was && enabled) backgroundedAt = 0L
            }
        }
    }

    fun canAuthenticate(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun onForeground() {
        if (!enabled) return
        val away = System.currentTimeMillis() - backgroundedAt
        if (backgroundedAt == 0L || away > GRACE_MS) _locked.value = true
    }

    fun onBackground() {
        backgroundedAt = System.currentTimeMillis()
    }

    fun unlock() {
        _locked.value = false
    }

    fun prompt(activity: FragmentActivity, title: String) {
        if (!canAuthenticate()) {
            // No credential set up: the lock cannot work, so do not trap the user.
            _locked.value = false
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _locked.value = false
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    companion object {
        /** Returning within this window does not re-lock (e.g. after a share sheet). */
        private const val GRACE_MS = 15_000L
    }
}
