package dev.quietinbox.feature.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.SectionHeader
import dev.quietinbox.platform.backup.BackupResult
import dev.quietinbox.platform.storage.settings.ThemeMode
import java.time.LocalDate

@Composable
fun SettingsScreen(
    onDeletedEverything: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state.settings
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    var mediaDisclosure by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf<android.net.Uri?>(null) }
    var timeDialog by remember { mutableStateOf(false) }
    var limitations by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }

    val fileName = stringResource(R.string.backup_file_name, LocalDate.now().toString())
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDialog = uri
    }

    val resultText = backupResultText(state.lastBackup)
    LaunchedEffect(state.lastBackup) {
        if (state.lastBackup != null && resultText != null) {
            snackbar.showSnackbar(resultText)
            viewModel.clearBackupResult()
        }
    }

    val demoText = demoResultText(state.lastDemo)
    LaunchedEffect(state.lastDemo) {
        if (state.lastDemo != null && demoText != null) {
            snackbar.showSnackbar(demoText)
            viewModel.clearDemoResult()
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
            item { SectionHeader(stringResource(R.string.section_appearance)) }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = s.themeMode == m,
                                onClick = { viewModel.setThemeMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                label = { Text(stringResource(when (m) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })) },
                            )
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                item { ToggleRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_desc), s.dynamicColor, viewModel::setDynamicColor) }
            }
            item { ToggleRow(stringResource(R.string.reduce_motion), stringResource(R.string.reduce_motion_desc), s.reduceMotion, viewModel::setReduceMotion) }

            item { SectionHeader(stringResource(R.string.section_privacy)) }
            item { ToggleRow(stringResource(R.string.ui_lock), stringResource(R.string.ui_lock_desc), s.uiLockEnabled, viewModel::setUiLock) }
            item { ToggleRow(stringResource(R.string.screenshot_protection), stringResource(R.string.screenshot_protection_desc), s.screenshotProtection, viewModel::setScreenshotProtection) }

            item { SectionHeader(stringResource(R.string.section_retention)) }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(stringResource(R.string.retention_days, s.retentionDays), style = MaterialTheme.typography.bodyLarge)
                    var sliderValue by remember(s.retentionDays) { mutableStateOf(s.retentionDays.toFloat()) }
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { viewModel.setRetentionDays(sliderValue.toInt()) },
                        valueRange = 1f..365f,
                        steps = 0,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in listOf(7, 30, 90, 365)) {
                            FilterChip(selected = s.retentionDays == preset, onClick = { viewModel.setRetentionDays(preset) }, label = { Text("$preset") })
                        }
                    }
                    Text(stringResource(R.string.retention_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.journal_ttl, s.journalTtlHours), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in listOf(6, 24, 72)) {
                            FilterChip(selected = s.journalTtlHours == preset, onClick = { viewModel.setJournalTtl(preset) }, label = { Text(stringResource(R.string.hours_short, preset)) })
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.section_media)) }
            item {
                ToggleRow(stringResource(R.string.media_copy), stringResource(R.string.media_copy_desc), s.mediaCopyEnabled) { enabled ->
                    if (enabled && !s.mediaDisclosureAccepted) mediaDisclosure = true else viewModel.setMediaCopy(enabled)
                }
            }

            item { SectionHeader(stringResource(R.string.section_reminders)) }
            item { ToggleRow(stringResource(R.string.reminders_enable), stringResource(R.string.reminders_desc), s.remindersEnabled, viewModel::setReminders) }
            if (s.remindersEnabled) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.reminder_time)) },
                        trailingContent = { TextButton(onClick = { timeDialog = true }) { Text("%02d:%02d".format(s.reminderHour, s.reminderMinute)) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(stringResource(R.string.reminder_days), style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (d in 1..7) {
                                FilterChip(
                                    selected = d in s.reminderWeekdays,
                                    onClick = { viewModel.toggleReminderDay(d) },
                                    label = { Text(stringResource(weekdayRes(d))) },
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.section_backup)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Key, null) },
                    headlineContent = { Text(stringResource(R.string.backup_recovery_key)) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.backup_recovery_key_desc))
                            if (state.recoveryKey != null) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), shape = MaterialTheme.shapes.medium) {
                                    Text(
                                        state.recoveryKey!!.ifBlank { "—" },
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                                Text(stringResource(R.string.backup_key_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { viewModel.acknowledgeRecoveryKey(); viewModel.hideRecoveryKey() }) { Text(stringResource(R.string.backup_key_ack)) }
                                    TextButton(onClick = viewModel::hideRecoveryKey) { Text(stringResource(R.string.action_hide)) }
                                }
                            } else {
                                TextButton(onClick = viewModel::showRecoveryKey) { Text(stringResource(R.string.backup_show_key)) }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Backup, null) },
                    headlineContent = { Text(stringResource(R.string.backup_export)) },
                    supportingContent = { Text(stringResource(R.string.backup_export_desc)) },
                    trailingContent = {
                        if (state.busy) LoadingIndicator() else TextButton(onClick = { exportLauncher.launch(fileName) }, enabled = s.recoveryKeyAcknowledged) { Text(stringResource(R.string.backup_export)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Restore, null) },
                    headlineContent = { Text(stringResource(R.string.backup_import)) },
                    supportingContent = { Text(stringResource(R.string.backup_import_desc)) },
                    trailingContent = { TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text(stringResource(R.string.backup_import)) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item { SectionHeader(stringResource(R.string.section_data)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(R.string.delete_everything), color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(stringResource(R.string.delete_everything_desc)) },
                    trailingContent = { TextButton(onClick = { deleteDialog = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            item { SectionHeader(stringResource(R.string.section_about)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.WifiOff, null) },
                    headlineContent = { Text(stringResource(R.string.about_version, state.versionName)) },
                    supportingContent = { Text(stringResource(R.string.about_offline)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    headlineContent = { Text(stringResource(R.string.about_limitations)) },
                    trailingContent = { TextButton(onClick = { limitations = true }) { Text(stringResource(R.string.action_show)) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    headlineContent = { Text(stringResource(R.string.about_licenses)) },
                    trailingContent = { TextButton(onClick = { licenses = true }) { Text(stringResource(R.string.action_show)) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Text(stringResource(R.string.about_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }

            // Debug builds only: the synthetic demo vault used for screenshots and walkthroughs.
            if (state.developerTools) {
                item { SectionHeader(stringResource(R.string.section_developer)) }
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Science, null) },
                        headlineContent = { Text(stringResource(R.string.dev_seed_demo)) },
                        supportingContent = { Text(stringResource(R.string.dev_seed_demo_desc), style = MaterialTheme.typography.bodySmall) },
                        trailingContent = { TextButton(onClick = viewModel::seedDemo, enabled = !state.busy) { Text(stringResource(R.string.dev_run)) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.DeleteSweep, null) },
                        headlineContent = { Text(stringResource(R.string.dev_clear_demo)) },
                        supportingContent = { Text(stringResource(R.string.dev_clear_demo_desc), style = MaterialTheme.typography.bodySmall) },
                        trailingContent = { TextButton(onClick = viewModel::clearDemo, enabled = !state.busy) { Text(stringResource(R.string.dev_run)) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (mediaDisclosure) {
        AlertDialog(
            onDismissRequest = { mediaDisclosure = false },
            title = { Text(stringResource(R.string.media_disclosure_title)) },
            text = { Text(stringResource(R.string.media_disclosure_body)) },
            confirmButton = { TextButton(onClick = { viewModel.acceptMediaDisclosure(); viewModel.setMediaCopy(true); mediaDisclosure = false }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { mediaDisclosure = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleteDialog) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text(stringResource(R.string.delete_everything_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_everything_confirm_body))
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteDialog = false; viewModel.deleteEverything(onDeletedEverything) }, enabled = typed == "DELETE") {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    importDialog?.let { uri ->
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importDialog = null },
            title = { Text(stringResource(R.string.backup_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_import_key_prompt))
                    OutlinedTextField(value = key, onValueChange = { key = it }, minLines = 3, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.import(uri, key); importDialog = null }, enabled = key.isNotBlank()) { Text(stringResource(R.string.backup_import)) } },
            dismissButton = { TextButton(onClick = { importDialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (timeDialog) {
        val picker = rememberTimePickerState(initialHour = s.reminderHour, initialMinute = s.reminderMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timeDialog = false },
            text = { TimePicker(state = picker) },
            confirmButton = { TextButton(onClick = { viewModel.setReminderTime(picker.hour, picker.minute); timeDialog = false }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (limitations) {
        AlertDialog(
            onDismissRequest = { limitations = false },
            title = { Text(stringResource(R.string.about_limitations)) },
            text = { Text(stringResource(R.string.about_limitations_body)) },
            confirmButton = { TextButton(onClick = { limitations = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (licenses) {
        AlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text(stringResource(R.string.about_licenses)) },
            text = { Text(stringResource(R.string.about_licenses_body)) },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun backupResultText(result: BackupResult?): String? = when (result) {
    null -> null
    is BackupResult.Ok -> stringResource(R.string.backup_result_ok, result.counts.conversations, result.counts.messages, result.counts.media)
    is BackupResult.Failed -> when (result.reason) {
        BackupResult.Reason.NO_RECOVERY_KEY, BackupResult.Reason.KEY_UNAVAILABLE -> stringResource(R.string.backup_failed_no_key)
        BackupResult.Reason.WRONG_KEY_OR_TAMPERED -> stringResource(R.string.backup_failed_key)
        BackupResult.Reason.CORRUPT -> stringResource(R.string.backup_failed_corrupt)
        BackupResult.Reason.TRUNCATED -> stringResource(R.string.backup_failed_truncated)
        BackupResult.Reason.IO, BackupResult.Reason.BAD_HEADER -> stringResource(R.string.backup_failed_io)
        BackupResult.Reason.UNSUPPORTED_VERSION -> stringResource(R.string.backup_failed_version)
        BackupResult.Reason.COUNT_MISMATCH -> stringResource(R.string.backup_failed_counts)
        BackupResult.Reason.TOO_LARGE -> stringResource(R.string.backup_failed_too_large)
        BackupResult.Reason.VAULT_UNAVAILABLE -> stringResource(R.string.backup_failed_vault)
    }
}

@Composable
private fun demoResultText(result: DemoResult?): String? = when (result) {
    null -> null
    is DemoResult.Seeded -> stringResource(R.string.dev_result_seeded, result.conversations, result.messages)
    DemoResult.Cleared -> stringResource(R.string.dev_result_cleared)
    is DemoResult.Failed -> stringResource(R.string.dev_result_failed, result.reason)
}

private fun weekdayRes(day: Int): Int = when (day) {
    1 -> R.string.weekday_1
    2 -> R.string.weekday_2
    3 -> R.string.weekday_3
    4 -> R.string.weekday_4
    5 -> R.string.weekday_5
    6 -> R.string.weekday_6
    else -> R.string.weekday_7
}
