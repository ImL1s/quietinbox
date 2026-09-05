package dev.quietinbox.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.quietinbox.core.analytics.ActivityAnalytics
import dev.quietinbox.core.analytics.ActivityReport
import dev.quietinbox.core.analytics.AnalyticsInput
import dev.quietinbox.core.analytics.BestTimeReport
import dev.quietinbox.core.analytics.ChattinessRank
import dev.quietinbox.core.analytics.EmojiCount
import dev.quietinbox.core.analytics.ObservedMessage
import dev.quietinbox.core.analytics.Period
import dev.quietinbox.core.analytics.PeriodKind
import dev.quietinbox.core.analytics.QuietRank
import dev.quietinbox.core.analytics.RankingBoards
import dev.quietinbox.core.analytics.SenderPhrases
import dev.quietinbox.platform.storage.repo.AnalyticsRepository
import dev.quietinbox.platform.storage.repo.ConversationLabel
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.InboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import javax.inject.Inject

/** The five views of the same period; the period selector is shared by all of them. */
enum class AnalyticsTab { OVERVIEW, RANKINGS, BEST_TIME, CHATTINESS, QUIET }

/** What the user picked in the period row; the dates are only used by [PeriodKind.CUSTOM]. */
data class PeriodSelection(
    val kind: PeriodKind = PeriodKind.LAST_7_DAYS,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
)

data class AnalyticsUiState(
    val loading: Boolean = true,
    val selection: PeriodSelection = PeriodSelection(),
    val period: Period? = null,
    val dayCount: Int = 0,
    val report: ActivityReport? = null,
    val heatmap: List<List<Int>> = emptyList(),
    val rankings: RankingBoards? = null,
    val bestTime: BestTimeReport? = null,
    val chattiness: List<ChattinessRank> = emptyList(),
    val quiet: List<QuietRank> = emptyList(),
    val catchphrases: List<SenderPhrases> = emptyList(),
    val emoji: List<EmojiCount> = emptyList(),
    val labels: Map<Long, ConversationLabel> = emptyMap(),
    /** True when the period held more messages than [AnalyticsRepository.MESSAGE_CAP]; only the newest ones were counted. */
    val capped: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val health: HealthRepository,
    inbox: InboxRepository,
) : ViewModel() {
    private val selection = MutableStateFlow(PeriodSelection())

    /**
     * Recomputes whenever the period changes or the message count changes. Everything downstream is
     * pure Kotlin over rows already in memory, but catchphrase scanning over "All" is real work, so
     * the whole pipeline runs off the main thread.
     */
    // Recompute when the selection changes or the vault changes, but never more than twice a second:
    // a burst of notifications must not re-scan the whole period for every single message.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val state: StateFlow<AnalyticsUiState> = combine(selection, inbox.observeCounts().catch { }.distinctUntilChanged()) { s, _ -> s }
        .debounce(400)
        .map { s -> compute(s) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun setPeriod(kind: PeriodKind) {
        if (kind == PeriodKind.CUSTOM) return
        selection.value = PeriodSelection(kind)
    }

    fun setCustomPeriod(start: LocalDate, end: LocalDate) {
        selection.value = PeriodSelection(PeriodKind.CUSTOM, start, end)
    }

    private suspend fun compute(s: PeriodSelection): AnalyticsUiState {
        val zone = TimeZone.currentSystemDefault()
        val now = System.currentTimeMillis()
        val period = period(s, now, zone)
        val messages: List<ObservedMessage> =
            runCatching { analytics.messagesBetween(period.startEpochMs, period.endEpochMsInclusive) }
                .getOrDefault(emptyList())
        val gaps = runCatching { health.observeGaps(GAP_LIMIT).first() }.getOrDefault(emptyList()).filter { gap ->
            (gap.startEpochMs ?: period.startEpochMs) < period.endEpochMsExclusive &&
                (gap.endEpochMs ?: period.endEpochMsInclusive) >= period.startEpochMs
        }
        val summaries = runCatching { analytics.summaryCountBetween(period.startEpochMs, period.endEpochMsInclusive) }.getOrDefault(0)

        val report = ActivityAnalytics.compute(
            AnalyticsInput(
                messages = messages,
                summaryOnlyCount = summaries,
                gaps = gaps,
                rangeStartEpochMs = period.startEpochMs,
                rangeEndEpochMs = period.endEpochMsInclusive,
                timeZone = zone,
            ),
        )
        val rankings = ActivityAnalytics.rankings(messages, period, zone).let {
            RankingBoards(it.allDays.take(TOP_ROWS), it.weekdays.take(TOP_ROWS), it.weekends.take(TOP_ROWS))
        }
        val bestTime = ActivityAnalytics.bestTime(messages, zone).let {
            BestTimeReport(it.perConversation.take(TOP_ROWS), it.distribution)
        }
        val chattiness = ActivityAnalytics.chattiness(messages, zone).take(TOP_ROWS)
        val quiet = ActivityAnalytics.quietRate(messages, period, zone).take(TOP_ROWS)

        val ids = buildSet {
            addAll(report.topConversations.map { it.conversationId })
            addAll(rankings.allDays.map { it.conversationId })
            addAll(rankings.weekdays.map { it.conversationId })
            addAll(rankings.weekends.map { it.conversationId })
            addAll(bestTime.perConversation.map { it.conversationId })
            addAll(chattiness.map { it.conversationId })
            addAll(quiet.map { it.conversationId })
        }
        val labels = runCatching { analytics.labels(ids) }.getOrDefault(emptyMap())

        return AnalyticsUiState(
            loading = false,
            selection = s,
            capped = messages.size >= AnalyticsRepository.MESSAGE_CAP,
            period = period,
            dayCount = period.days(zone).size,
            report = report,
            heatmap = ActivityAnalytics.heatmap(messages, zone).map { it.toList() },
            rankings = rankings,
            bestTime = bestTime,
            chattiness = chattiness,
            quiet = quiet,
            catchphrases = ActivityAnalytics.catchphrases(messages).take(TOP_SENDERS),
            emoji = ActivityAnalytics.emojiRanking(messages),
            labels = labels,
        )
    }

    private suspend fun period(s: PeriodSelection, now: Long, zone: TimeZone): Period = when (s.kind) {
        PeriodKind.LAST_7_DAYS -> Period.last7Days(now, zone)
        PeriodKind.THIS_MONTH -> Period.thisMonth(now, zone)
        PeriodKind.LAST_MONTH -> Period.lastMonth(now, zone)
        PeriodKind.LAST_3_MONTHS -> Period.last3Months(now, zone)
        PeriodKind.ALL -> Period.all(
            earliestEpochMs = runCatching { analytics.earliestTimestamp() }.getOrNull(),
            nowEpochMs = now,
            zone = zone,
        )
        PeriodKind.CUSTOM ->
            if (s.start != null && s.end != null) Period.custom(s.start, s.end, zone)
            else Period.last7Days(now, zone)
    }

    private companion object {
        /** Rows kept per board; every listed conversation must have a label, and labels cost one query each. */
        const val TOP_ROWS = 20
        const val TOP_SENDERS = 12
        const val GAP_LIMIT = 200
    }
}
