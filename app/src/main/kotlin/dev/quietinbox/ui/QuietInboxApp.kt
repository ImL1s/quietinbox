package dev.quietinbox.ui

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.LoadingScreen
import dev.quietinbox.core.designsystem.theme.QuietInboxTheme
import dev.quietinbox.core.designsystem.theme.QuietThemeMode
import dev.quietinbox.feature.onboarding.OnboardingScreen
import dev.quietinbox.platform.storage.settings.AppSettings
import dev.quietinbox.platform.storage.settings.SettingsRepository
import dev.quietinbox.platform.storage.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    settings: SettingsRepository,
    val lock: LockController,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settings.settings.map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun QuietInboxApp(activity: FragmentActivity, viewModel: AppViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val locked by viewModel.lock.locked.collectAsStateWithLifecycle()
    val s = settings
    val mode = when (s?.themeMode) {
        ThemeMode.LIGHT -> QuietThemeMode.LIGHT
        ThemeMode.DARK -> QuietThemeMode.DARK
        else -> QuietThemeMode.SYSTEM
    }
    // System bar icon contrast must follow the app theme, not only the OS dark-mode flag.
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        QuietThemeMode.LIGHT -> false
        QuietThemeMode.DARK -> true
        QuietThemeMode.SYSTEM -> systemDark
    }
    LaunchedEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        )
    }
    QuietInboxTheme(
        mode = mode,
        dynamicColor = s?.dynamicColor ?: false,
        reduceMotion = s?.reduceMotion ?: false,
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                s == null -> LoadingScreen()
                locked -> LockScreen(onUnlock = { viewModel.lock.prompt(activity, activity.getString(R.string.lock_prompt_title)) }, canAuthenticate = viewModel.lock.canAuthenticate())
                !s.onboardingCompleted -> OnboardingScreen(onFinished = {})
                else -> MainNavigation()
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit, canAuthenticate: Boolean) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier.size(120.dp).clip(MaterialShapes.Clover4Leaf.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp)) }
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
            if (!canAuthenticate) Text(stringResource(R.string.lock_unavailable), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onUnlock) { Text(stringResource(R.string.lock_button)) }
        }
    }
}
