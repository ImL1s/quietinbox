package dev.quietinbox.platform.storage.repo

import dev.quietinbox.core.model.GapInterval
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.platform.storage.db.CaptureSessionEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DiagnosticCount
import dev.quietinbox.platform.storage.db.DiagnosticEventEntity
import dev.quietinbox.platform.storage.db.GapIntervalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class CaptureSession(val id: Long, val generation: String, val startedAtEpochMs: Long, val endedAtEpochMs: Long?, val endReason: String?)

/** Capture sessions, gap intervals and body-free diagnostics. */
@Singleton
class HealthRepository @Inject constructor(
    private val holder: DatabaseHolder,
) {
    fun observeGaps(limit: Int = 50): Flow<List<GapInterval>> =
        holder.flowWithDb { db -> db.healthDao().observeGaps(limit) }.map { rows -> rows.map { it.toDomain() } }

    fun observeSessions(limit: Int = 20): Flow<List<CaptureSession>> =
        holder.flowWithDb { db -> db.healthDao().observeSessions(limit) }
            .map { rows -> rows.map { CaptureSession(it.id, it.generation, it.startedAtEpochMs, it.endedAtEpochMs, it.endReason) } }

    fun observePendingJournal(): Flow<Int> = holder.flowWithDb { db -> db.journalDao().observePendingCount() }

    fun observeDiagnostics(limit: Int = 100): Flow<List<DiagnosticEventEntity>> =
        holder.flowWithDb { db -> db.diagnosticsDao().observeRecent(limit) }

    suspend fun diagnosticCounts(since: Long): List<DiagnosticCount> = holder.db().diagnosticsDao().countsSince(since)

    suspend fun startSession(generation: String, bootSessionId: String, now: Long): Long {
        val db = holder.db()
        val id = db.healthDao().insertSession(CaptureSessionEntity(generation = generation, bootSessionId = bootSessionId, startedAtEpochMs = now, endedAtEpochMs = null, endReason = null))
        // Any other open session means the process died without closing: an unknown-length gap.
        val dangling = db.healthDao().openSession()
        if (dangling != null && dangling.id != id) {
            db.healthDao().endSession(dangling.id, null, "PROCESS_RESTART")
            openGap(null, GapReason.PROCESS_RESTART, GapPrecision.UNKNOWN, now)
        }
        return id
    }

    suspend fun endSession(id: Long, now: Long?, reason: String) = holder.db().healthDao().endSession(id, now, reason)

    suspend fun openGap(startEpochMs: Long?, reason: GapReason, precision: GapPrecision, now: Long): Long =
        holder.db().healthDao().insertGap(GapIntervalEntity(startEpochMs = startEpochMs, endEpochMs = null, reason = reason.name, precision = precision.name, createdAtEpochMs = now))

    suspend fun closeOpenGap(endEpochMs: Long?) {
        val db = holder.db()
        val open = db.healthDao().openGap() ?: return
        db.healthDao().closeGap(open.id, endEpochMs)
    }

    suspend fun recordGap(startEpochMs: Long?, endEpochMs: Long?, reason: GapReason, precision: GapPrecision, now: Long) {
        holder.db().healthDao().insertGap(GapIntervalEntity(startEpochMs = startEpochMs, endEpochMs = endEpochMs, reason = reason.name, precision = precision.name, createdAtEpochMs = now))
    }

    suspend fun diagnostic(code: String, detail: String? = null, packageName: String? = null, now: Long) {
        runCatching { holder.db().diagnosticsDao().insert(DiagnosticEventEntity(code = code, detail = detail, packageName = packageName, atEpochMs = now)) }
    }

    suspend fun summaryCountSince(since: Long): Int = holder.db().healthDao().summaryCountSince(since)
}
