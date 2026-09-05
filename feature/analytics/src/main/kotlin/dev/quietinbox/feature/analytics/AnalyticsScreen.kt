package dev.quietinbox.feature.analytics

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.analytics.ActivityReport
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.BarChart
import dev.quietinbox.core.designsystem.components.EmptyState
import dev.quietinbox.core.designsystem.components.LoadingScreen
import dev.quietinbox.core.designsystem.components.MonogramAvatar
import dev.quietinbox.core.designsystem.components.SectionHeader
import dev.quietinbox.core.designsystem.components.ShareBar
import dev.quietinbox.core.designsystem.components.SourceBadge
import dev.quietinbox.core.designsystem.components.StatTile
import dev.quietinbox.core.designsystem.components.TimeFormat
import dev.quietinbox.core.designsystem.components.rememberAppLabel

@Composable
fun AnalyticsScreen(
    onOpenConversation: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val report = state.report
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.analytics_title)) },
                subtitle = {
                    if (report != null) {
                        Text(
                            stringResource(R.string.analytics_sample_line, report.sampleSize - report.ambiguousCount, report.ambiguousCount, report.summaryOnlyCount),
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
        if (state.loading || report == null) {
            LoadingScreen(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "range") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val ranges = AnalyticsRange.entries
                    ranges.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = state.range == r,
                            onClick = { viewModel.setRange(r) },
                            shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                            label = {
                                Text(
                                    stringResource(
                                        when (r) {
                                            AnalyticsRange.DAYS_7 -> R.string.analytics_range_7d
                                            AnalyticsRange.DAYS_30 -> R.string.analytics_range_30d
                                            AnalyticsRange.DAYS_90 -> R.string.analytics_range_90d
                                            AnalyticsRange.ALL -> R.string.analytics_range_all
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                Text(
                    stringResource(R.string.analytics_range_line, TimeFormat.date(report.rangeStartEpochMs), TimeFormat.date(report.rangeEndEpochMs), report.timeZoneId),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            if (report.isEmpty) {
                item(key = "empty") {
                    EmptyState(title = stringResource(R.string.analytics_empty_title), body = stringResource(R.string.analytics_empty_body), icon = Icons.Outlined.Insights)
                }
                return@LazyColumn
            }
            item(key = "tiles") { Tiles(report) }
            item(key = "hourly") {
                SectionHeader(stringResource(R.string.analytics_hourly_title))
                Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    BarChart(
                        values = report.hourly,
                        labels = mapOf(0 to "0", 6 to "6", 12 to "12", 18 to "18", 23 to "23"),
                        description = stringResource(R.string.analytics_hourly_desc, report.hourly.joinToString(",")),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (report.daily.size > 1) {
                item(key = "daily") {
                    SectionHeader(stringResource(R.string.analytics_daily_title))
                    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        val days = report.daily.takeLast(31)
                        BarChart(
                            values = days.map { it.count },
                            labels = mapOf(0 to days.first().date.toString().substring(5), days.lastIndex to days.last().date.toString().substring(5)),
                            description = stringResource(R.string.analytics_daily_desc, days.joinToString(",") { "${it.date}:${it.count}" }),
                            modifier = Modifier.padding(16.dp),
                            height = 100.dp,
                        )
                    }
                }
            }
            if (report.topConversations.isNotEmpty()) {
                item(key = "top-conv") {
                    SectionHeader(stringResource(R.string.analytics_top_conversations))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (rank in report.topConversations) {
                            val label = state.labels[rank.conversationId]
                            val title = label?.title ?: stringResource(R.string.analytics_unknown_conversation)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().clickable { onOpenConversation(rank.conversationId) },
                            ) {
                                MonogramAvatar(title, "conv-${rank.conversationId}", size = 36.dp)
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        label?.let { SourceBadge(it.packageName, size = 14.dp) }
                                        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        Text(
                                            "${rank.count} · " + stringResource(R.string.analytics_share, (rank.share * 100).toInt()),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    ShareBar(rank.share.toFloat())
                                }
                            }
                        }
                    }
                }
            }
            if (report.topSenders.isNotEmpty()) {
                item(key = "senders") {
                    SectionHeader(stringResource(R.string.analytics_top_senders))
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (s in report.topSenders) SuggestionChip(onClick = {}, label = { Text("${s.name} · ${s.count}") })
                    }
                }
            }
            if (report.emoji.isNotEmpty()) {
                item(key = "emoji") {
                    SectionHeader(stringResource(R.string.analytics_emoji_title))
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (e in report.emoji) SuggestionChip(onClick = {}, label = { Text("${e.emoji} ${e.count}") })
                    }
                }
            }
            if (report.sources.size > 1) {
                item(key = "sources") {
                    SectionHeader(stringResource(R.string.analytics_tile_sources))
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (s in report.sources) {
                            SuggestionChip(onClick = {}, icon = { SourceBadge(s.packageName, size = 16.dp) }, label = { Text("${rememberAppLabel(s.packageName)} · ${s.count}") })
                        }
                    }
                }
            }
            item(key = "notes") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (report.gapCount > 0) {
                        Text(stringResource(R.string.analytics_gaps_note, report.gapCount, report.unknownGapCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(stringResource(R.string.analytics_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Tiles(report: ActivityReport) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile((report.sampleSize - report.ambiguousCount).toString(), stringResource(R.string.analytics_tile_captured), Modifier.weight(1f), icon = Icons.Outlined.Insights, container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
            StatTile(report.conversationCount.toString(), stringResource(R.string.analytics_tile_conversations), Modifier.weight(1f), icon = Icons.Outlined.Forum)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(report.sources.size.toString(), stringResource(R.string.analytics_tile_sources), Modifier.weight(1f), icon = Icons.Outlined.Apps)
            StatTile(median(report.medianIntervalMs), stringResource(R.string.analytics_tile_median), Modifier.weight(1f), icon = Icons.Outlined.Timer)
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
