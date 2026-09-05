package dev.quietinbox.feature.health

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.model.GapInterval
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.core.parser.ParserRegistry
import dev.quietinbox.parsers.apps.AppParsers
import dev.quietinbox.platform.capture.CaptureCoordinator
import dev.quietinbox.platform.capture.CaptureStatus
import dev.quietinbox.platform.capture.ListenerAccess
import dev.quietinbox.platform.capture.SyntheticNotifications
import dev.quietinbox.platform.crypto.KeyFailure
import dev.quietinbox.platform.storage.db.DiagnosticCount
import dev.quietinbox.platform.storage.db.VaultState
import dev.quietinbox.platform.storage.repo.HealthRepository
import dev.quietinbox.platform.storage.repo.SourceRepository
import dev.quietinbox.platform.storage.repo.VaultRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstalledApp(val packageName: String, val label: String, val hasAdapter: Boolean, val manual: Boolean = false)

data class HealthUiState(
    val capture: CaptureStatus = CaptureStatus(),
    val listenerGranted: Boolean = false,
    val vaultFailure: KeyFailure? = null,
    val sources: ImmutableList<SourceConfiguration> = persistentListOf(),
    val gaps: ImmutableList<GapInterval> = persistentListOf(),
    val pendingJournal: Int = 0,
    val diagnostics: ImmutableList<DiagnosticCount> = persistentListOf(),
    val testSentAt: Long? = null,
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: CaptureCoordinator,
    private val listenerAccess: ListenerAccess,
    private val sourceRepo: SourceRepository,
    private val health: HealthRepository,
    private val vault: VaultRepository,
    private val synthetic: SyntheticNotifications,
) : ViewModel() {
    private val registry = ParserRegistry(AppParsers.all())
    private val diagnostics = MutableStateFlow<List<DiagnosticCount>>(emptyList())
    private val testSentAt = MutableStateFlow<Long?>(null)

    val state: StateFlow<HealthUiState> = combine(
        coordinator.status,
        vault.state,
        sourceRepo.observeSources().catch { emit(emptyList()) },
        combine(health.observeGaps(30).catch { emit(emptyList()) }, health.observePendingJournal().catch { emit(0) }) { g, p -> g to p },
        combine(diagnostics, testSentAt) { d, t -> d to t },
    ) { capture, vaultState, sources, (gaps, pending), (diag, sent) ->
        HealthUiState(
            capture = capture,
            listenerGranted = listenerAccess.isGranted(),
            vaultFailure = (vaultState as? VaultState.Locked)?.failure,
            sources = sources.toImmutableList(),
            gaps = gaps.toImmutableList(),
            pendingJournal = pending,
            diagnostics = diag.toImmutableList(),
            testSentAt = sent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState())

    init {
        refreshDiagnostics()
    }

    fun refresh() {
        coordinator.refreshPermissionState()
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() = viewModelScope.launch {
        diagnostics.value = runCatching { health.diagnosticCounts(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000) }.getOrDefault(emptyList())
    }

    fun settingsIntent(): Intent = listenerAccess.settingsIntent()
    fun appInfoIntent(): Intent = listenerAccess.appInfoIntent()

    fun setPaused(paused: Boolean) = coordinator.setPaused(paused)

    fun setSourceEnabled(packageName: String, enabled: Boolean) = viewModelScope.launch { runCatching { sourceRepo.setEnabled(packageName, enabled) } }
    fun setSourcePaused(packageName: String, paused: Boolean) = viewModelScope.launch { runCatching { sourceRepo.setPaused(packageName, paused) } }

    fun addSource(app: InstalledApp) = viewModelScope.launch {
        runCatching { sourceRepo.enable(app.packageName, app.label, registry.adapterFor(app.packageName)?.id, System.currentTimeMillis()) }
    }

    fun removeSource(packageName: String, deleteData: Boolean) = viewModelScope.launch {
        runCatching { sourceRepo.remove(packageName, deleteData) }
    }

    fun canPostNotifications(): Boolean = synthetic.canPost()

    fun sendTest() {
        synthetic.postConversation(count = 3, iconRes = R.drawable.ic_stat_quiet)
        testSentAt.value = System.currentTimeMillis()
    }

    fun retryVault() = viewModelScope.launch { vault.retryOpen() }
    fun resetVault() = viewModelScope.launch { vault.resetAfterKeyFailure() }

    /** Body-free diagnostic summary suitable for a bug report. */
    fun diagnosticsSummary(): String {
        val s = state.value
        return buildString {
            appendLine("QuietInbox diagnostics (no message content)")
            appendLine("listener=${s.capture.listenerState} granted=${s.listenerGranted} vaultLocked=${s.vaultFailure != null}")
            appendLine("queue=${s.capture.queueDepth} accepted=${s.capture.acceptedCount} overflow=${s.capture.overflowCount} droppedAfterRevoke=${s.capture.droppedAfterRevoke} pendingJournal=${s.pendingJournal}")
            appendLine("sources=" + s.sources.joinToString { "${it.packageName}:${if (it.enabled) "on" else "off"}${if (it.paused) "(paused)" else ""}:${it.adapterId ?: "standard"}" })
            appendLine("parsers=" + registry.all.joinToString { "${it.id}@${it.version}" })
            appendLine("gaps=" + s.gaps.joinToString { "${it.reason}/${it.precision}" })
            for (d in s.diagnostics) appendLine("${d.code}=${d.n}")
            appendLine("android=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }
    }

    suspend fun installedApps(query: String): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val existing = state.value.sources.map { it.packageName }.toSet()
        val q = query.trim().lowercase()
        val launchable = runCatching {
            pm.getInstalledApplications(0)
        }.getOrDefault(emptyList())
            .filter { it.packageName != context.packageName && it.packageName !in existing }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName in KnownSources.ALL || isMessagingLike(it.packageName) }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString(), registry.adapterFor(it.packageName) != null) }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }
            .sortedWith(compareByDescending<InstalledApp> { it.hasAdapter }.thenBy { it.label.lowercase() })
        // Power-user path: a package the launcher cannot see (package visibility rules, or a
        // package without a launcher activity) can still be added by its exact name.
        val looksLikePackage = q.matches(Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+"""))
        if (looksLikePackage && q !in existing && launchable.none { it.packageName == q }) {
            launchable + InstalledApp(q, q, registry.adapterFor(q) != null, manual = true)
        } else {
            launchable
        }
    }

    private fun isMessagingLike(packageName: String): Boolean =
        listOf("messag", "chat", "talk", "mail", "sms", "mms", "signal", "line", "telegram", "whatsapp", "discord", "slack", "wechat", "kakao", "viber").any { packageName.contains(it) }
}
