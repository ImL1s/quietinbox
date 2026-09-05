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
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.AnalyticsRepository
import dev.quietinbox.platform.storage.repo.ConversationLabel
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.InboxCounts
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.transformLatest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

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
    /** The encrypted vault could not be opened; nothing can be computed until it is unlocked. */
    val vaultLocked: Boolean = false,
    /** A query failed during this computation, so the report may be incomplete (shown as an honesty label). */
    val degraded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val health: HealthRepository,
    private val inbox: InboxRepository,
    private val vault: VaultRepository,
) : ViewModel() {
    private val selection = MutableStateFlow(PeriodSelection())

    /** The chosen period, exposed directly so the chip row reflects a tap at once even while a slow query is still being cancelled. */
    val selectedPeriod: StateFlow<PeriodSelection> = selection.asStateFlow()

    /**
     * Recomputes whenever the period changes or the message count changes. Everything downstream is
     * pure Kotlin over rows already in memory, but catchphrase scanning over "All" is real work, so
     * the whole pipeline runs off the main thread.
     */
    // A period switch recomputes at once; vault changes are sampled (first at once, then at most one
    // recomputation per 400 ms) so a burst of notifications cannot re-scan the period per message.
    private var last = AnalyticsUiState()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val state: StateFlow<AnalyticsUiState> = combine(selection, vaultSignals()) { s, v -> s to v }
        .transformLatest { (s, v) ->
            when (v) {
                // A locked vault is a terminal, explained state — never an endless spinner.
                is VaultState.Locked -> { emit(AnalyticsUiState(loading = false, selection = s, vaultLocked = true).also { last = it }); return@transformLatest }
                // Still opening: keep the loading state; the first count tick arrives once it is Ready.
                VaultState.Opening -> { emit(AnalyticsUiState(loading = true, selection = s).also { last = it }); return@transformLatest }
                is VaultState.Ready -> Unit
            }
            // A period switch shows a clean loading placeholder at once (no report, no quality label
            // carried over from the previous period) and cancels the previous computation; a
            // background vault change recomputes quietly behind the current content.
            // `last` is read and written only here: transformLatest cancels and joins the previous
            // block before starting the next, so no other coroutine touches it.
            if (s != last.selection || last.report == null) emit(AnalyticsUiState(loading = true, selection = s).also { last = it })
            emit(compute(s).also { last = it })
        }
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
        val degradation = Degradation()
        val period = period(s, now, zone, degradation)
        val messages: List<ObservedMessage> =
            degradation.orDefault(emptyList()) { analytics.messagesBetween(period.startEpochMs, period.endEpochMsInclusive) }
        val gaps = degradation.orDefault(emptyList()) { health.observeGaps(GAP_LIMIT).first() }.filter { gap ->
            (gap.startEpochMs ?: period.startEpochMs) < period.endEpochMsExclusive &&
                (gap.endEpochMs ?: period.endEpochMsInclusive) >= period.startEpochMs
        }
        val summaries = degradation.orDefault(0) { analytics.summaryCountBetween(period.startEpochMs, period.endEpochMsInclusive) }

        coroutineContext.ensureActive()
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
        coroutineContext.ensureActive()
        val rankings = ActivityAnalytics.rankings(messages, period, zone).let {
            RankingBoards(it.allDays.take(TOP_ROWS), it.weekdays.take(TOP_ROWS), it.weekends.take(TOP_ROWS))
        }
        val bestTime = ActivityAnalytics.bestTime(messages, zone).let {
            BestTimeReport(it.perConversation.take(TOP_ROWS), it.distribution)
        }
        val chattiness = ActivityAnalytics.chattiness(messages, zone).take(TOP_ROWS)
        val quiet = ActivityAnalytics.quietRate(messages, period, zone).take(TOP_ROWS)
        coroutineContext.ensureActive()
        val heatmap = ActivityAnalytics.heatmap(messages, zone).map { it.toList() }
        val catchphrases = ActivityAnalytics.catchphrases(messages).take(TOP_SENDERS)
        coroutineContext.ensureActive()
        val emoji = ActivityAnalytics.emojiRanking(messages)

        val ids = buildSet {
            addAll(report.topConversations.map { it.conversationId })
            addAll(rankings.allDays.map { it.conversationId })
            addAll(rankings.weekdays.map { it.conversationId })
            addAll(rankings.weekends.map { it.conversationId })
            addAll(bestTime.perConversation.map { it.conversationId })
            addAll(chattiness.map { it.conversationId })
            addAll(quiet.map { it.conversationId })
        }
        val labels = degradation.orDefault(emptyMap()) { analytics.labels(ids) }

        return AnalyticsUiState(
            loading = false,
            selection = s,
            capped = messages.size >= AnalyticsRepository.MESSAGE_CAP,
            period = period,
            dayCount = period.days(zone).size,
            report = report,
            heatmap = heatmap,
            rankings = rankings,
            bestTime = bestTime,
            chattiness = chattiness,
            quiet = quiet,
            catchphrases = catchphrases,
            emoji = emoji,
            labels = labels,
            degraded = degradation.any,
        )
    }

    /** Records whether any query in one computation failed, so the report can say it may be incomplete. */
    private class Degradation { var any = false }

    /**
     * A failed query degrades the report to [default] instead of crashing the screen — and marks the
     * computation as degraded so the UI can say so — but a cancellation (period switched, screen
     * left) must still unwind the whole computation.
     */
    private inline fun <T> Degradation.orDefault(default: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) { // JVM Errors (OOM, linkage) keep propagating
        any = true
        default
    }

    private suspend fun period(s: PeriodSelection, now: Long, zone: TimeZone, degradation: Degradation): Period = when (s.kind) {
        PeriodKind.LAST_7_DAYS -> Period.last7Days(now, zone)
        PeriodKind.THIS_MONTH -> Period.thisMonth(now, zone)
        PeriodKind.LAST_MONTH -> Period.lastMonth(now, zone)
        PeriodKind.LAST_3_MONTHS -> Period.last3Months(now, zone)
        PeriodKind.ALL -> Period.all(
            earliestEpochMs = degradation.orDefault(null) { analytics.earliestTimestamp() },
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

    /**
     * What drives a recomputation. The vault state is the outer signal: every non-Ready state is
     * emitted as such (a locked or opening vault is shown, never an endless spinner), and every
     * transition to Ready starts a fresh inner subscription whose first count arrives at once (the
     * shared flow replays it) — so unlocking recovers the page even when the counts did not change.
     * Later counts are sampled every 400 ms (never starved). A failing count query emits a fallback
     * tick and retries with back-off, so an error neither leaves the page loading nor stops the ticks.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun vaultSignals(): Flow<VaultState> {
        var consecutiveFailures = 0
        val counts = inbox.observeCounts()
            .onEach { consecutiveFailures = 0 } // back-off restarts after any successful emission
            .retryWhen { _, _ ->
                emit(InboxCounts(0, 0, 0, 0))
                delay((1_000L shl consecutiveFailures).coerceAtMost(30_000L))
                consecutiveFailures = minOf(consecutiveFailures + 1, 5) // 1 s, 2 s, … capped at 30 s
                true
            }
            .distinctUntilChanged()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
        return vault.state.flatMapLatest { v ->
            if (v !is VaultState.Ready) flowOf(v)
            else merge(counts.take(1), counts.drop(1).sample(400)).map { v }
        }
    }
}
