package dev.quietinbox.feature.analytics

import dev.quietinbox.platform.storage.repo.AnalyticsRepository
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.analytics.ActivityReport
import dev.quietinbox.core.analytics.ChattinessRank
import dev.quietinbox.core.analytics.ConversationRank
import dev.quietinbox.core.analytics.PeriodKind
import dev.quietinbox.core.analytics.QuietRank
import dev.quietinbox.core.analytics.TimeBand
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.BarChart
import dev.quietinbox.core.designsystem.components.EmptyState
import dev.quietinbox.core.designsystem.components.HeatMapChart
import dev.quietinbox.core.designsystem.components.HeatMapLegend
import dev.quietinbox.core.designsystem.components.LoadingScreen
import dev.quietinbox.core.designsystem.components.MonogramAvatar
import dev.quietinbox.core.designsystem.components.SectionHeader
import dev.quietinbox.core.designsystem.components.ShareBar
import dev.quietinbox.core.designsystem.components.SourceBadge
import dev.quietinbox.core.designsystem.components.StatTile
import dev.quietinbox.core.designsystem.components.TimeFormat
import dev.quietinbox.core.designsystem.components.rememberAppLabel
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Locale

@Composable
fun AnalyticsScreen(
    onOpenConversation: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val tabs = AnalyticsTab.entries
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val tab = tabs[selectedTab.coerceIn(0, tabs.lastIndex)]
    val report = state.report

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.analytics_title)) },
                subtitle = {
                    if (report != null) {
                        Text(
                            stringResource(
                                R.string.analytics_sample_line,
                                report.sampleSize - report.ambiguousCount,
                                report.ambiguousCount,
                                report.summaryOnlyCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                tabs.forEachIndexed { index, entry ->
                    Tab(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index },
                        text = { Text(stringResource(entry.labelRes()), maxLines = 1) },
                    )
                }
            }
            PeriodRow(
                selected = state.selection.kind,
                onSelect = viewModel::setPeriod,
                onCustom = { pickerOpen = true },
            )
            Text(
                stringResource(tab.subtitleRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (state.loading || report == null) {
                LoadingScreen()
                return@Column
            }
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp)
            if (report.isEmpty) {
                EmptyState(
                    title = stringResource(R.string.analytics_empty_messages_title),
                    body = stringResource(R.string.analytics_empty_messages_body),
                    icon = Icons.Outlined.Insights,
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = bottom,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    AnalyticsTab.OVERVIEW -> overviewTab(state, report)
                    AnalyticsTab.RANKINGS -> rankingsTab(state, onOpenConversation)
                    AnalyticsTab.BEST_TIME -> bestTimeTab(state, onOpenConversation)
                    AnalyticsTab.CHATTINESS -> chattinessTab(state, onOpenConversation)
                    AnalyticsTab.QUIET -> quietTab(state, onOpenConversation)
                }
            }
        }
    }

    if (pickerOpen) {
        RangePickerDialog(
            onDismiss = { pickerOpen = false },
            onConfirm = { start, end ->
                viewModel.setCustomPeriod(start, end)
                pickerOpen = false
            },
        )
    }
}

// ------------------------------------------------------------------------------- period row

@Composable
private fun PeriodRow(
    selected: PeriodKind,
    onSelect: (PeriodKind) -> Unit,
    onCustom: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (kind in PRESET_PERIODS) {
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text(stringResource(kind.labelRes()), maxLines = 1) },
            )
        }
        FilterChip(
            selected = selected == PeriodKind.CUSTOM,
            onClick = onCustom,
            label = { Text(stringResource(R.string.analytics_period_custom), maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
        )
    }
}

@Composable
private fun RangePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (kotlinx.datetime.LocalDate, kotlinx.datetime.LocalDate) -> Unit,
) {
    val pickerState = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis ?: return@TextButton
                    val end = pickerState.selectedEndDateMillis ?: start
                    onConfirm(utcDate(start), utcDate(end))
                },
            ) { Text(stringResource(R.string.analytics_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.analytics_dialog_cancel)) }
        },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
            title = {
                Text(
                    stringResource(R.string.analytics_period_pick),
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

/** `DateRangePicker` hands back UTC midnights; the calendar day is the one in UTC, not the phone's. */
private fun utcDate(epochMs: Long) =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC).date

// ---------------------------------------------------------------------------------- overview

private fun LazyListScope.overviewTab(state: AnalyticsUiState, report: ActivityReport) {
    item(key = "ov-range") { RangeLine(state, report) }
    item(key = "ov-tiles") { Tiles(report) }
    item(key = "ov-hourly") {
        SectionHeader(stringResource(R.string.analytics_hourly_title))
        ChartCard {
            BarChart(
                values = report.hourly,
                labels = mapOf(0 to "0", 6 to "6", 12 to "12", 18 to "18", 23 to "23"),
                description = stringResource(R.string.analytics_hourly_desc, report.hourly.joinToString(",")),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    item(key = "ov-heatmap") {
        SectionHeader(stringResource(R.string.analytics_heatmap_title))
        val weekdays = WEEKDAY_LABELS.map { stringResource(it) }
        val rowSummary = state.heatmap.mapIndexed { row, counts ->
            stringResource(R.string.analytics_heatmap_row_desc, weekdays.getOrElse(row) { "" }, counts.sum().toString())
        }.joinToString("; ")
        ChartCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HeatMapChart(
                    values = state.heatmap,
                    rowLabels = weekdays,
                    description = stringResource(R.string.analytics_heatmap_desc, rowSummary),
                    columnLabels = mapOf(0 to "0", 6 to "6", 12 to "12", 18 to "18"),
                )
                HeatMapLegend(
                    lowLabel = stringResource(R.string.analytics_heatmap_less),
                    highLabel = stringResource(R.string.analytics_heatmap_more),
                )
            }
        }
    }
    if (report.daily.size > 1) {
        item(key = "ov-daily") {
            SectionHeader(stringResource(R.string.analytics_daily_title))
            ChartCard {
                val days = report.daily.takeLast(31)
                BarChart(
                    values = days.map { it.count },
                    labels = mapOf(
                        0 to days.first().date.toString().substring(5),
                        days.lastIndex to days.last().date.toString().substring(5),
                    ),
                    description = stringResource(
                        R.string.analytics_daily_desc,
                        days.joinToString(",") { "${it.date}:${it.count}" },
                    ),
                    modifier = Modifier.padding(16.dp),
                    height = 100.dp,
                )
            }
        }
    }
    if (report.topSenders.isNotEmpty()) {
        item(key = "ov-senders") {
            SectionHeader(stringResource(R.string.analytics_top_senders))
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (sender in report.topSenders) {
                    SuggestionChip(onClick = {}, label = { Text("${sender.name} · ${sender.count}") })
                }
            }
        }
    }
    if (state.emoji.isNotEmpty()) {
        item(key = "ov-emoji") {
            SectionHeader(stringResource(R.string.analytics_emoji_title))
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (emoji in state.emoji) {
                    SuggestionChip(onClick = {}, label = { Text("${emoji.emoji} ${emoji.count}") })
                }
            }
        }
    }
    if (state.catchphrases.isNotEmpty()) {
        item(key = "ov-phrases") {
            SectionHeader(stringResource(R.string.analytics_catchphrase_title))
            Note(stringResource(R.string.analytics_catchphrase_note))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (sender in state.catchphrases) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.analytics_catchphrase_sender, sender.sender, sender.messageCount),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (phrase in sender.phrases) {
                                SuggestionChip(onClick = {}, label = { Text("${phrase.phrase} · ${phrase.count}") })
                            }
                        }
                    }
                }
            }
        }
    }
    if (report.sources.size > 1) {
        item(key = "ov-sources") {
            SectionHeader(stringResource(R.string.analytics_tile_sources))
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (source in report.sources) {
                    SuggestionChip(
                        onClick = {},
                        icon = { SourceBadge(source.packageName, size = 16.dp) },
                        label = { Text("${rememberAppLabel(source.packageName)} · ${source.count}") },
                    )
                }
            }
        }
    }
    item(key = "ov-notes") {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (report.gapCount > 0) {
                Text(
                    stringResource(R.string.analytics_gaps_note, report.gapCount, report.unknownGapCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                stringResource(R.string.analytics_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------- rankings

private fun LazyListScope.rankingsTab(state: AnalyticsUiState, onOpen: (Long) -> Unit) {
    val boards = state.rankings ?: return
    board("rank-all", R.string.analytics_board_all, boards.allDays, state, onOpen)
    board("rank-weekday", R.string.analytics_board_weekdays, boards.weekdays, state, onOpen)
    board("rank-weekend", R.string.analytics_board_weekends, boards.weekends, state, onOpen)
}

private fun LazyListScope.board(
    key: String,
    @StringRes title: Int,
    ranks: List<ConversationRank>,
    state: AnalyticsUiState,
    onOpen: (Long) -> Unit,
) {
    item(key = key) {
        SectionHeader(stringResource(title))
        if (ranks.isEmpty()) {
            Note(stringResource(R.string.analytics_empty_messages_title))
        } else {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (rank in ranks) {
                    ConversationRow(
                        conversationId = rank.conversationId,
                        state = state,
                        onOpen = onOpen,
                        trailing = stringResource(R.string.analytics_share, (rank.share * 100).roundToInt()),
                        secondary = stringResource(R.string.analytics_observed_count, rank.count),
                        share = rank.share.toFloat(),
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------- best time

private fun LazyListScope.bestTimeTab(state: AnalyticsUiState, onOpen: (Long) -> Unit) {
    val best = state.bestTime ?: return
    if (best.distribution.sumOf { it.count } == 0) {
        item(key = "band-empty") {
            SectionHeader(stringResource(R.string.analytics_band_distribution_title))
            Note(stringResource(R.string.analytics_empty_messages_title))
        }
        return
    }
    item(key = "band-dist") {
        SectionHeader(stringResource(R.string.analytics_band_distribution_title))
        val labels = best.distribution.map { stringResource(it.band.labelRes()) }
        ChartCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BarChart(
                    values = best.distribution.map { it.count },
                    description = stringResource(
                        R.string.analytics_band_desc,
                        best.distribution.mapIndexed { i, band -> "${labels[i]}:${band.count}" }.joinToString(","),
                    ),
                    height = 96.dp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    best.distribution.forEachIndexed { i, band ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text("${labels[i]} · ${(band.share * 100).roundToInt()}%") },
                        )
                    }
                }
            }
        }
    }
    item(key = "band-conv") {
        SectionHeader(stringResource(R.string.analytics_top_conversations))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (entry in best.perConversation) {
                ConversationRow(
                    conversationId = entry.conversationId,
                    state = state,
                    onOpen = onOpen,
                    trailing = stringResource(entry.band.labelRes()),
                    secondary = stringResource(
                        R.string.analytics_band_dominant,
                        stringResource(entry.band.labelRes()),
                        (entry.share * 100).roundToInt(),
                        entry.total,
                    ),
                    share = entry.share.toFloat(),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------- chattiness

private fun LazyListScope.chattinessTab(state: AnalyticsUiState, onOpen: (Long) -> Unit) {
    item(key = "chat-note") {
        SectionHeader(stringResource(R.string.analytics_chattiness_title))
        Note(stringResource(R.string.analytics_chattiness_formula))
    }
    if (state.chattiness.isEmpty()) {
        item(key = "chat-empty") { Note(stringResource(R.string.analytics_empty_messages_title)) }
        return
    }
    item(key = "chat-rows") {
        val top = state.chattiness.maxOfOrNull { it.perActiveDay } ?: 1.0
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (rank in state.chattiness) {
                ConversationRow(
                    conversationId = rank.conversationId,
                    state = state,
                    onOpen = onOpen,
                    trailing = stringResource(R.string.analytics_chattiness_value, oneDecimal(rank.perActiveDay)),
                    secondary = stringResource(R.string.analytics_chattiness_days, rank.activeDays, rank.count),
                    share = shareOf(rank, top),
                )
            }
        }
    }
}

private fun shareOf(rank: ChattinessRank, top: Double): Float =
    if (top <= 0.0) 0f else (rank.perActiveDay / top).toFloat()

// -------------------------------------------------------------------------------- quiet rate

private fun LazyListScope.quietTab(state: AnalyticsUiState, onOpen: (Long) -> Unit) {
    item(key = "quiet-note") {
        SectionHeader(stringResource(R.string.analytics_quiet_title))
        Note(stringResource(R.string.analytics_quiet_formula))
    }
    if (state.quiet.isEmpty()) {
        item(key = "quiet-empty") { Note(stringResource(R.string.analytics_empty_messages_title)) }
        return
    }
    item(key = "quiet-rows") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (rank in state.quiet) {
                ConversationRow(
                    conversationId = rank.conversationId,
                    state = state,
                    onOpen = onOpen,
                    trailing = stringResource(R.string.analytics_quiet_value, (rank.rate * 100).roundToInt()),
                    secondary = quietSecondary(rank),
                    share = rank.rate.toFloat(),
                )
            }
        }
    }
}

@Composable
private fun quietSecondary(rank: QuietRank): String =
    stringResource(R.string.analytics_quiet_days, rank.quietDays, rank.totalDays) + " · " +
        stringResource(R.string.analytics_quiet_streak, rank.longestQuietStreakDays)

// ----------------------------------------------------------------------------------- pieces

@Composable
private fun ConversationRow(
    conversationId: Long,
    state: AnalyticsUiState,
    onOpen: (Long) -> Unit,
    trailing: String,
    secondary: String,
    share: Float,
) {
    val label = state.labels[conversationId]
    val title = label?.title ?: stringResource(R.string.analytics_unknown_conversation)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen(conversationId) },
    ) {
        MonogramAvatar(title, "conv-$conversationId", size = 36.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                label?.let { SourceBadge(it.packageName, size = 14.dp) }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            ShareBar(share.coerceIn(0f, 1f))
        }
    }
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { content() }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun RangeLine(state: AnalyticsUiState, report: ActivityReport) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Text(
            stringResource(
                R.string.analytics_range_line,
                TimeFormat.date(report.rangeStartEpochMs),
                TimeFormat.date(report.rangeEndEpochMs),
                report.timeZoneId,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.analytics_period_days, state.dayCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.capped) {
            // Honesty label: the period was truncated to the newest MESSAGE_CAP messages.
            Text(
                stringResource(R.string.analytics_capped, AnalyticsRepository.MESSAGE_CAP),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Tiles(report: ActivityReport) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                (report.sampleSize - report.ambiguousCount).toString(),
                stringResource(R.string.analytics_tile_captured),
                Modifier.weight(1f),
                icon = Icons.Outlined.Insights,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            StatTile(
                report.conversationCount.toString(),
                stringResource(R.string.analytics_tile_conversations),
                Modifier.weight(1f),
                icon = Icons.Outlined.Forum,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                report.sources.size.toString(),
                stringResource(R.string.analytics_tile_sources),
                Modifier.weight(1f),
                icon = Icons.Outlined.Apps,
            )
            StatTile(
                median(report.medianIntervalMs),
                stringResource(R.string.analytics_tile_median),
                Modifier.weight(1f),
                icon = Icons.Outlined.Timer,
            )
        }
    }
}

@Composable
private fun median(ms: Long?): String {
    ms ?: return "—"
    return when {
        ms < 60_000 -> stringResource(R.string.analytics_seconds, ms / 1000)
        ms < 3_600_000 -> stringResource(R.string.analytics_minutes, ms / 60_000)
        else -> stringResource(R.string.analytics_hours, ms / 3_600_000)
    }
}

private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private val PRESET_PERIODS = listOf(
    PeriodKind.LAST_7_DAYS,
    PeriodKind.THIS_MONTH,
    PeriodKind.LAST_MONTH,
    PeriodKind.LAST_3_MONTHS,
    PeriodKind.ALL,
)

private val WEEKDAY_LABELS = listOf(
    R.string.analytics_weekday_mon,
    R.string.analytics_weekday_tue,
    R.string.analytics_weekday_wed,
    R.string.analytics_weekday_thu,
    R.string.analytics_weekday_fri,
    R.string.analytics_weekday_sat,
    R.string.analytics_weekday_sun,
)

@StringRes
private fun AnalyticsTab.labelRes(): Int = when (this) {
    AnalyticsTab.OVERVIEW -> R.string.analytics_tab_overview
    AnalyticsTab.RANKINGS -> R.string.analytics_tab_rankings
    AnalyticsTab.BEST_TIME -> R.string.analytics_tab_best_time
    AnalyticsTab.CHATTINESS -> R.string.analytics_tab_chattiness
    AnalyticsTab.QUIET -> R.string.analytics_tab_quiet
}

@StringRes
private fun AnalyticsTab.subtitleRes(): Int = when (this) {
    AnalyticsTab.OVERVIEW -> R.string.analytics_sub_overview
    AnalyticsTab.RANKINGS -> R.string.analytics_sub_rankings
    AnalyticsTab.BEST_TIME -> R.string.analytics_sub_best_time
    AnalyticsTab.CHATTINESS -> R.string.analytics_sub_chattiness
    AnalyticsTab.QUIET -> R.string.analytics_sub_quiet
}

@StringRes
private fun PeriodKind.labelRes(): Int = when (this) {
    PeriodKind.LAST_7_DAYS -> R.string.analytics_period_7d
    PeriodKind.THIS_MONTH -> R.string.analytics_period_this_month
    PeriodKind.LAST_MONTH -> R.string.analytics_period_last_month
    PeriodKind.LAST_3_MONTHS -> R.string.analytics_period_3m
    PeriodKind.ALL -> R.string.analytics_period_all
    PeriodKind.CUSTOM -> R.string.analytics_period_custom
}

@StringRes
private fun TimeBand.labelRes(): Int = when (this) {
    TimeBand.MORNING -> R.string.analytics_band_morning
    TimeBand.LEISURE -> R.string.analytics_band_leisure
    TimeBand.AFTERNOON -> R.string.analytics_band_afternoon
    TimeBand.EVENING -> R.string.analytics_band_evening
    TimeBand.NIGHT -> R.string.analytics_band_night
}
