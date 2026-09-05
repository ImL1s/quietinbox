package dev.quietinbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.EmptyState
import dev.quietinbox.feature.analytics.AnalyticsScreen
import dev.quietinbox.feature.conversation.ConversationScreen
import dev.quietinbox.feature.health.HealthScreen
import dev.quietinbox.feature.inbox.InboxScreen
import dev.quietinbox.feature.search.SearchScreen
import dev.quietinbox.feature.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object InboxRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object AnalyticsRoute : NavKey
@Serializable data object HealthRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data class ConversationRoute(val id: Long) : NavKey

private data class TopLevel(val route: NavKey, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val topLevel = listOf(
    TopLevel(InboxRoute, R.string.nav_inbox, Icons.Outlined.Inbox, Icons.Filled.Inbox),
    TopLevel(SearchRoute, R.string.nav_search, Icons.Outlined.Search, Icons.Filled.Search),
    TopLevel(AnalyticsRoute, R.string.nav_analytics, Icons.Outlined.Insights, Icons.Filled.Insights),
    TopLevel(HealthRoute, R.string.nav_health, Icons.Outlined.Sensors, Icons.Filled.Sensors),
    TopLevel(SettingsRoute, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * Navigation 3 back stack with a bottom bar on compact windows, a wide rail on medium/expanded
 * windows, and a list-detail scene so the inbox and a conversation sit side by side on tablets
 * and unfolded devices (plan section 12).
 */
@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(InboxRoute)
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val wide = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val current = backStack.lastOrNull()
    val currentTop = backStack.lastOrNull { key -> topLevel.any { it.route == key } } ?: InboxRoute
    val showChrome = current !is ConversationRoute || wide

    fun goTop(route: NavKey) {
        if (backStack.lastOrNull() == route) return
        backStack.clear()
        if (route != InboxRoute) backStack.add(InboxRoute)
        backStack.add(route)
    }

    val listDetail = rememberListDetailSceneStrategy<NavKey>()
    val display: @Composable (Modifier) -> Unit = { modifier ->
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            sceneStrategies = listOf(listDetail),
            entryProvider = entryProvider {
                entry<InboxRoute>(
                    metadata = ListDetailSceneStrategy.listPane(
                        detailPlaceholder = {
                            EmptyState(
                                title = stringResource(R.string.detail_placeholder_title),
                                body = stringResource(R.string.detail_placeholder_body),
                                icon = Icons.Outlined.Forum,
                            )
                        },
                    ),
                ) {
                    InboxScreen(
                        onOpenConversation = { id ->
                            if (backStack.lastOrNull() is ConversationRoute) backStack.removeLastOrNull()
                            backStack.add(ConversationRoute(id))
                        },
                        onOpenHealth = { goTop(HealthRoute) },
                    )
                }
                entry<ConversationRoute>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                    ConversationScreen(
                        conversationId = key.id,
                        onBack = { backStack.removeLastOrNull() },
                        showBackButton = !wide,
                    )
                }
                entry<SearchRoute> {
                    SearchScreen(onOpenConversation = { backStack.add(ConversationRoute(it)) })
                }
                entry<AnalyticsRoute> {
                    AnalyticsScreen(onOpenConversation = { backStack.add(ConversationRoute(it)) })
                }
                entry<HealthRoute> { HealthScreen() }
                entry<SettingsRoute> {
                    SettingsScreen(onDeletedEverything = { goTop(InboxRoute) })
                }
            },
        )
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                for (item in topLevel) {
                    NavigationRailItem(
                        selected = currentTop == item.route,
                        onClick = { goTop(item.route) },
                        icon = { Icon(if (currentTop == item.route) item.selectedIcon else item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
            Box(Modifier.weight(1f)) { display(Modifier.fillMaxSize()) }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { display(Modifier.fillMaxSize()) }
            if (showChrome) {
                NavigationBar(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    for (item in topLevel) {
                        NavigationBarItem(
                            selected = currentTop == item.route,
                            onClick = { goTop(item.route) },
                            icon = { Icon(if (currentTop == item.route) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        }
    }
}
