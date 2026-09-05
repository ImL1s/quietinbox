package dev.quietinbox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.quietinbox.platform.storage.settings.SettingsRepository
import dev.quietinbox.ui.LockController
import dev.quietinbox.ui.QuietInboxApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-activity host. A [FragmentActivity] is required by `BiometricPrompt`; Compose runs
 * inside it as usual.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var lock: LockController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Screenshot / Recents protection follows the user's setting (default on). Debug builds
        // leave the window capturable so QA can take screenshots; release builds always honour it.
        lifecycleScope.launch {
            settings.settings.map { it.screenshotProtection }.distinctUntilChanged().collect { protect ->
                if (protect && !BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        setContent {
            QuietInboxApp(activity = this)
        }
    }

    override fun onStart() {
        super.onStart()
        lock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        lock.onBackground()
    }
}
