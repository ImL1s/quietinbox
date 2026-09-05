package dev.quietinbox.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.EmptyState
import dev.quietinbox.core.designsystem.components.MonogramAvatar
import dev.quietinbox.core.designsystem.components.SourceBadge
import dev.quietinbox.core.designsystem.components.relativeTime
import dev.quietinbox.core.designsystem.components.rememberAppLabel
import dev.quietinbox.core.model.SearchNormalizer
import dev.quietinbox.platform.storage.repo.SearchHit

@Composable
fun SearchScreen(
    onOpenConversation: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(modifier = modifier) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Outlined.Clear, stringResource(R.string.action_close)) }
                },
                singleLine = true,
                shape = SearchBarDefaults.inputFieldShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val ranges = SearchRange.entries
                ranges.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = state.range == r,
                        onClick = { viewModel.setRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                        label = {
                            Text(
                                stringResource(
                                    when (r) {
                                        SearchRange.ALL -> R.string.search_range_all
                                        SearchRange.TODAY -> R.string.search_range_today
                                        SearchRange.DAYS_7 -> R.string.search_range_7d
                                        SearchRange.DAYS_30 -> R.string.search_range_30d
                                    },
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            if (state.availablePackages.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = state.packages.isEmpty(), onClick = viewModel::clearPackages, label = { Text(stringResource(R.string.search_all_sources)) }) }
                    items(state.availablePackages, key = { it }) { p ->
                        FilterChip(selected = p in state.packages, onClick = { viewModel.togglePackage(p) }, label = { Text(rememberAppLabel(p)) }, leadingIcon = { SourceBadge(p, size = 18.dp) })
                    }
                }
            }
            when {
                state.query.isBlank() -> EmptyState(
                    title = stringResource(R.string.nav_search),
                    body = stringResource(R.string.search_empty_hint),
                    icon = Icons.Outlined.Search,
                )
                state.searching && !state.searched -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { LoadingIndicator() }
                state.results.isEmpty() -> EmptyState(
                    title = stringResource(R.string.search_no_results, state.query),
                    body = stringResource(R.string.search_empty_hint),
                    icon = Icons.Outlined.SearchOff,
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 96.dp)) {
                    item {
                        Text(
                            stringResource(R.string.search_results_count, state.results.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(state.results, key = { it.message.id }) { hit ->
                        HitRow(hit, state.query, onClick = { onOpenConversation(hit.message.conversationId) })
                        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, query: String, onClick: () -> Unit) {
    val highlight = MaterialTheme.colorScheme.primary
    val body = hit.message.body
    val normalizedQuery = SearchNormalizer.normalize(query)
    val annotated = buildAnnotatedString {
        val lower = SearchNormalizer.normalize(body)
        // Highlight only when the normalised body has the same length as the raw one (no width folding changed offsets).
        val idx = if (lower.length == body.length) lower.indexOf(normalizedQuery) else -1
        if (idx < 0) {
            append(body)
        } else {
            append(body.substring(0, idx))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight)) { append(body.substring(idx, idx + normalizedQuery.length)) }
            append(body.substring(idx + normalizedQuery.length))
        }
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { MonogramAvatar(hit.conversationTitle, hit.conversationTitle ?: hit.packageName, size = 40.dp) },
        overlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceBadge(hit.packageName, size = 14.dp)
                Text(hit.conversationTitle ?: stringResource(R.string.analytics_unknown_conversation), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        headlineContent = { Text(annotated, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = { hit.message.senderName?.let { Text(it, style = MaterialTheme.typography.labelSmall) } },
        trailingContent = { Text(relativeTime(hit.message.sortKey), style = MaterialTheme.typography.labelSmall) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
    @Suppress("UNUSED_VARIABLE") val unused = Modifier.size(0.dp)
}
