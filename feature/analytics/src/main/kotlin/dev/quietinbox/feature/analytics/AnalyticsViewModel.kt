package dev.quietinbox.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.core.analytics.ActivityAnalytics
import dev.quietinbox.core.analytics.ActivityReport
import dev.quietinbox.core.analytics.AnalyticsInput
import dev.quietinbox.platform.storage.repo.AnalyticsRepository
import dev.quietinbox.platform.storage.repo.ConversationLabel
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.InboxRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import javax.inject.Inject

enum class AnalyticsRange(val days: Int?) { DAYS_7(7), DAYS_30(30), DAYS_90(90), ALL(null) }

data class AnalyticsUiState(
    val loading: Boolean = true,
    val range: AnalyticsRange = AnalyticsRange.DAYS_30,
    val report: ActivityReport? = null,
    val labels: Map<Long, ConversationLabel> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val health: HealthRepository,
    inbox: InboxRepository,
) : ViewModel() {
    private val range = MutableStateFlow(AnalyticsRange.DAYS_30)

    /** Recomputes whenever the range changes or the message count changes (cheap, local). */
    val state: StateFlow<AnalyticsUiState> = combine(range, inbox.observeCounts().catch { }) { r, _ -> r }
        .map { r -> compute(r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun setRange(r: AnalyticsRange) {
        range.value = r
    }

    private suspend fun compute(r: AnalyticsRange): AnalyticsUiState {
        val now = System.currentTimeMillis()
        val start = r.days?.let { now - it.toLong() * 24 * 60 * 60 * 1000 } ?: (runCatching { analytics.earliestTimestamp() }.getOrNull() ?: now)
        val messages = runCatching { analytics.messagesBetween(start, now) }.getOrDefault(emptyList())
        val gaps = runCatching { health.observeGaps(200).first() }.getOrDefault(emptyList()).filter { g ->
            (g.startEpochMs ?: start) <= now && (g.endEpochMs ?: now) >= start
        }
        val summaries = runCatching { analytics.summaryCountSince(start) }.getOrDefault(0)
        val report = ActivityAnalytics.compute(
            AnalyticsInput(
                messages = messages,
                summaryOnlyCount = summaries,
                gaps = gaps,
                rangeStartEpochMs = start,
                rangeEndEpochMs = now,
                timeZone = TimeZone.currentSystemDefault(),
            ),
        )
        val labels = runCatching { analytics.labels(report.topConversations.map { it.conversationId }) }.getOrDefault(emptyMap())
        return AnalyticsUiState(loading = false, range = r, report = report, labels = labels)
    }
}
