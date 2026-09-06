package dev.quietinbox.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.QualityTag
import dev.quietinbox.core.designsystem.components.SourceBadge
import dev.quietinbox.core.designsystem.theme.QualityColors

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settingsMissing by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermission()
        onPauseOrDispose { }
    }
    BackHandler(enabled = state.step > 0) { viewModel.back() }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) viewModel.sendTest() }
    val sendTest: () -> Unit = {
        viewModel.persistSources()
        if (Build.VERSION.SDK_INT >= 33 && !state.canPostNotifications) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.sendTest()
    }

    Scaffold(modifier = modifier) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.ob_step_of, state.step + 1, state.stepCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearWavyProgressIndicator(
                    progress = { (state.step + 1f) / state.stepCount },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "onboarding-step",
            ) { step ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (step) {
                        0 -> ScopeStep()
                        1 -> SourcesStep(state, viewModel::toggle)
                        2 -> AccessStep(state, settingsMissing = settingsMissing, onOpen = { settingsMissing = !viewModel.openListenerSettings(context) })
                        3 -> TestStep(state, sendTest)
                        else -> PreviewStep()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (state.step > 0) TextButton(onClick = viewModel::back) { Text(stringResource(R.string.ob_back)) } else Spacer(Modifier.width(1.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.step == 3 && !state.testSent) TextButton(onClick = viewModel::next) { Text(stringResource(R.string.ob_skip)) }
                    val last = state.step == state.stepCount - 1
                    Button(
                        onClick = { if (last) viewModel.finish(onFinished) else viewModel.next() },
                        enabled = when (state.step) {
                            2 -> state.granted
                            else -> true
                        },
                    ) { Text(stringResource(if (last) R.string.ob_finish else R.string.ob_next)) }
                }
            }
        }
    }
}

@Composable
private fun Illustration(icon: ImageVector, shape: androidx.compose.ui.graphics.Shape) {
    Box(
        Modifier
            .size(112.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(52.dp))
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScopeStep() {
    Illustration(Icons.Outlined.Shield, MaterialShapes.Clover8Leaf.toShape())
    StepTitle(stringResource(R.string.ob_scope_title), stringResource(R.string.ob_scope_body))
    Point(Icons.Outlined.WifiOff, stringResource(R.string.ob_scope_point_1))
    Point(Icons.Outlined.Visibility, stringResource(R.string.ob_scope_point_2))
    Point(Icons.Outlined.Label, stringResource(R.string.ob_scope_point_3))
    Point(Icons.Outlined.Lock, stringResource(R.string.ob_scope_point_4))
}

@Composable
private fun SourcesStep(state: OnboardingUiState, onToggle: (String) -> Unit) {
    Illustration(Icons.Outlined.NotificationsActive, MaterialShapes.Pill.toShape())
    StepTitle(stringResource(R.string.ob_sources_title), stringResource(R.string.ob_sources_body))
    Column {
        for (c in state.choices) {
            ListItem(
                leadingContent = { SourceBadge(c.packageName, size = 40.dp) },
                headlineContent = { Text(c.label) },
                supportingContent = {
                    if (!c.installed) Text(stringResource(R.string.ob_sources_not_installed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (c.hasAdapter) QualityTag(stringResource(R.string.health_known_source), Icons.Outlined.CheckCircle, QualityColors.verified)
                },
                trailingContent = { Checkbox(checked = c.packageName in state.selected, onCheckedChange = { onToggle(c.packageName) }, enabled = c.installed) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
    }
}

@Composable
private fun AccessStep(state: OnboardingUiState, settingsMissing: Boolean, onOpen: () -> Unit) {
    Illustration(Icons.Outlined.Lock, MaterialShapes.Sunny.toShape())
    StepTitle(stringResource(R.string.ob_access_title), stringResource(R.string.ob_access_body))
    if (state.granted) {
        QualityTag(stringResource(R.string.ob_access_granted), Icons.Outlined.CheckCircle, QualityColors.verified)
    } else {
        FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.ob_access_button)) }
        if (settingsMissing) {
            Text(stringResource(R.string.listener_settings_manual), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Text(stringResource(R.string.health_restricted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TestStep(state: OnboardingUiState, onSend: () -> Unit) {
    Illustration(Icons.Outlined.Science, MaterialShapes.Cookie7Sided.toShape())
    StepTitle(stringResource(R.string.ob_test_title), stringResource(R.string.ob_test_body))
    FilledTonalButton(onClick = onSend, enabled = state.granted) { Text(stringResource(R.string.ob_test_button)) }
    if (!state.canPostNotifications && Build.VERSION.SDK_INT >= 33) {
        Text(stringResource(R.string.ob_notification_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.testSent) {
        if (state.capturedMessages > 0) {
            QualityTag(stringResource(R.string.ob_test_captured, state.capturedMessages), Icons.Outlined.CheckCircle, QualityColors.verified)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.ob_test_waiting), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PreviewStep() {
    Illustration(Icons.Outlined.Visibility, MaterialShapes.Ghostish.toShape())
    StepTitle(stringResource(R.string.ob_preview_title), stringResource(R.string.ob_preview_body))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QualityTag(stringResource(R.string.identity_inferred), Icons.Outlined.Label, QualityColors.inferred)
        QualityTag(stringResource(R.string.conv_preview_restricted), Icons.Outlined.Visibility, QualityColors.uncertain)
        QualityTag(stringResource(R.string.conv_ambiguous_tag), Icons.Outlined.Label, QualityColors.uncertain)
    }
}
