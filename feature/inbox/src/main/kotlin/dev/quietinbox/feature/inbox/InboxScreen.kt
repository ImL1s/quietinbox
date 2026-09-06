package dev.quietinbox.feature.inbox

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.EmptyState
import dev.quietinbox.core.designsystem.components.LoadingScreen
import dev.quietinbox.core.designsystem.components.MonogramAvatar
import dev.quietinbox.core.designsystem.components.NoticeBanner
import dev.quietinbox.core.designsystem.components.QualityTag
import dev.quietinbox.core.designsystem.components.SourceBadge
import dev.quietinbox.core.designsystem.components.TimeFormat
import dev.quietinbox.core.designsystem.components.currentLocale
import dev.quietinbox.core.designsystem.components.identityLabel
import dev.quietinbox.core.designsystem.components.rememberAppLabel
import dev.quietinbox.core.designsystem.components.relativeTime
import dev.quietinbox.core.designsystem.theme.QualityColors
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.IdentityConfidence
import dev.quietinbox.core.model.ListenerState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    onOpenConversation: (Long) -> Unit,
    onOpenHealth: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settingsMissing by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermission()
        onPauseOrDispose { }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.sendTestNotification()
    }
    val sendTest: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !viewModel.canPostNotifications()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.sendTestNotification()
        }
    }

    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(if (state.filter.archived) R.string.inbox_filter_archived else R.string.inbox_title)) },
                subtitle = {
                    Text(
                        summaryLine(state),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingScreen(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
            item(key = "banner") { HealthBanner(state, onOpenHealth) }
            item(key = "filters") {
                SourceFilters(
                    packages = state.availablePackages,
                    selected = state.filter.packages,
                    archived = state.filter.archived,
                    onToggle = viewModel::togglePackage,
                    onAll = viewModel::clearPackages,
                    onArchived = viewModel::setArchived,
                )
            }
            if (state.conversations.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(if (state.filter.archived) R.string.inbox_empty_archived_title else R.string.inbox_empty_title),
                        body = stringResource(R.string.inbox_empty_body),
                        icon = Icons.Outlined.Inbox,
                        actions = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!state.listenerGranted) {
                                    if (settingsMissing) {
                                        Text(stringResource(R.string.listener_settings_manual), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    Button(onClick = { settingsMissing = !viewModel.openListenerSettings(context) }) {
                                        Icon(Icons.Outlined.LockOpen, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.inbox_grant_access))
                                    }
                                }
                                FilledTonalButton(onClick = sendTest, enabled = state.listenerGranted) {
                                    Icon(Icons.Outlined.Send, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.inbox_send_test))
                                }
                            }
                        },
                    )
                }
            } else {
                items(state.conversations, key = { it.id }) { c ->
                    ConversationRow(
                        conversation = c,
                        onClick = { onOpenConversation(c.id) },
                        onPin = { viewModel.setPinned(c.id, !c.pinned) },
                        onArchive = { viewModel.setArchivedConversation(c.id, !c.archived) },
                        onDelete = { pendingDelete = c },
                        modifier = Modifier.animateItem(),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 84.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    pendingDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.inbox_delete_title)) },
            text = { Text(stringResource(R.string.inbox_delete_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteConversation(c.id); pendingDelete = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun summaryLine(state: InboxUiState): String {
    // Two plurals, not one string with two counts: English needs "1 message" and "1 observation"
    // singular, and the sentence about uncertain identity is dropped entirely when there is none of it
    // rather than read "plus 0 observations".
    val saved = state.counts.messages - state.counts.ambiguous
    val savedText = pluralStringResource(R.plurals.inbox_summary_saved, saved, saved)
    val base = if (state.counts.ambiguous == 0) {
        savedText
    } else {
        stringResource(
            R.string.inbox_summary_join,
            savedText,
            pluralStringResource(R.plurals.inbox_summary_uncertain, state.counts.ambiguous, state.counts.ambiguous),
        )
    }
    val gap = state.latestGap ?: return base
    val gapText = if (gap.startEpochMs != null) {
        stringResource(
            R.string.inbox_summary_gap,
            TimeFormat.time(gap.startEpochMs!!, locale = currentLocale()),
            gap.endEpochMs?.let { TimeFormat.time(it, locale = currentLocale()) } ?: stringResource(R.string.health_gap_open),
        )
    } else {
        stringResource(R.string.inbox_summary_gap_unknown)
    }
    // Joined through the same locale-aware string as the two halves above: Chinese and Japanese do
    // not want the ASCII space that English and Korean do.
    return stringResource(R.string.inbox_summary_join, base, gapText)
}

@Composable
private fun HealthBanner(state: InboxUiState, onOpenHealth: () -> Unit) {
    val context = LocalContext.current
    when {
        state.vaultLocked -> NoticeBanner(
            text = stringResource(R.string.inbox_banner_locked),
            icon = Icons.Outlined.SyncProblem,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            action = { TextButton(onClick = onOpenHealth) { Text(stringResource(R.string.nav_health)) } },
        )
        !state.listenerGranted -> NoticeBanner(
            text = stringResource(R.string.inbox_banner_not_granted),
            icon = Icons.Outlined.NotificationsOff,
            action = { TextButton(onClick = onOpenHealth) { Text(stringResource(R.string.action_settings)) } },
        )
        state.capture.listenerState == ListenerState.PAUSED -> NoticeBanner(
            text = stringResource(R.string.inbox_banner_paused),
            icon = Icons.Outlined.PauseCircle,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            action = { TextButton(onClick = onOpenHealth) { Text(stringResource(R.string.nav_health)) } },
        )
        state.capture.listenerState == ListenerState.GRANTED_DISCONNECTED || state.capture.listenerState == ListenerState.RECONNECTING -> NoticeBanner(
            text = stringResource(R.string.inbox_banner_disconnected),
            icon = Icons.Outlined.SyncProblem,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Unit
    }
    @Suppress("UNUSED_VARIABLE") val unused = context
}

@Composable
private fun SourceFilters(
    packages: List<String>,
    selected: Set<String>,
    archived: Boolean,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onArchived: (Boolean) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected.isEmpty() && !archived,
                onClick = { onAll(); onArchived(false) },
                label = { Text(stringResource(R.string.inbox_filter_all)) },
            )
        }
        items(packages, key = { it }) { pkg ->
            val label = rememberAppLabel(pkg)
            FilterChip(
                selected = pkg in selected,
                onClick = { onToggle(pkg) },
                label = { Text(label) },
                leadingIcon = { SourceBadge(pkg, size = 18.dp) },
            )
        }
        item {
            FilterChip(
                selected = archived,
                onClick = { onArchived(!archived) },
                label = { Text(stringResource(R.string.inbox_filter_archived)) },
                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val title = conversation.title ?: stringResource(R.string.analytics_unknown_conversation)
    val identity = identityLabel(conversation.identityConfidence)
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box {
                MonogramAvatar(label = conversation.title, key = conversation.identityKey, size = 52.dp)
                SourceBadge(
                    packageName = conversation.scope.packageName,
                    size = 20.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(1.5.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (conversation.isGroup == true) {
                        Icon(Icons.Outlined.Group, contentDescription = stringResource(R.string.inbox_group), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (conversation.scope.profileKey != PRIMARY_PROFILE) {
                        Icon(Icons.Outlined.Work, contentDescription = stringResource(R.string.inbox_work_profile), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (conversation.hasUnviewed) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.pinned) {
                        Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.inbox_pinned), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                val preview = buildString {
                    conversation.lastSenderName?.let { append(it).append(": ") }
                    append(conversation.lastMessagePreview ?: "")
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (conversation.hasUnviewed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = conversation.identityConfidence != IdentityConfidence.VERIFIED_SOURCE_ID || conversation.ambiguousCount > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                        if (conversation.identityConfidence != IdentityConfidence.VERIFIED_SOURCE_ID) {
                            QualityTag(identity.text, identity.icon, identity.tint)
                        }
                        if (conversation.ambiguousCount > 0) {
                            QualityTag(
                                pluralStringResource(R.plurals.ambiguous_count_plural, conversation.ambiguousCount, conversation.ambiguousCount),
                                Icons.Outlined.ContentCopy,
                                QualityColors.uncertain,
                            )
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    relativeTime(conversation.lastActivityEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.hasUnviewed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.hasUnviewed) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (conversation.pinned) R.string.action_unpin else R.string.action_pin)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                onClick = { menu = false; onPin() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (conversation.archived) R.string.action_unarchive else R.string.action_archive)) },
                leadingIcon = { Icon(if (conversation.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                onClick = { menu = false; onArchive() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}

/** `UserHandle.hashCode()` of the device owner; anything else is a work (or secondary) profile. */
private const val PRIMARY_PROFILE = "user:0"
