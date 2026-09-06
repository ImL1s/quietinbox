package dev.quietinbox.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.feature.settings.ReminderScheduling
import dev.quietinbox.platform.storage.repo.InboxRepository
import dev.quietinbox.platform.storage.settings.AppSettings
import dev.quietinbox.platform.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuietInbox's own, opt-in daily reminder (plan section 13). Off by default, computed in the
 * device's local time zone (so DST and zone changes shift with the clock), delivered through a
 * normal notification channel so Do Not Disturb applies, and never touching capture state.
 * The reminder carries no synthetic marker, so the listener ignores it: no feedback loop.
 *
 * A reminder is only posted when there is something to look at: conversations of the chosen
 * sources (or of all sources) with copies newer than the last time they were opened
 * (QI-REMIND-015). The next run is scheduled before the worker returns.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ReminderScheduling {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun reschedule() {
        scope.launch { rescheduleNow() }
    }

    /** Schedules (or cancels) the next run and returns once WorkManager has the request. */
    suspend fun rescheduleNow() {
        val s = settings.current()
        val wm = WorkManager.getInstance(context)
        if (!s.remindersEnabled || s.reminderWeekdays.isEmpty()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = delayUntilNext(s, ZonedDateTime.now(ZoneId.systemDefault()))
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "quietinbox.reminder"
        const val CHANNEL_ID = "quietinbox.reminders"

        /** Pure function: next occurrence of HH:MM on an enabled ISO weekday, strictly after [now]. */
        fun delayUntilNext(s: AppSettings, now: ZonedDateTime): Duration {
            val time = LocalTime.of(s.reminderHour, s.reminderMinute)
            for (offset in 0..7) {
                val day = now.toLocalDate().plusDays(offset.toLong())
                if (day.dayOfWeek.value !in s.reminderWeekdays) continue
                val candidate = ZonedDateTime.of(LocalDateTime.of(day, time), now.zone)
                if (candidate.isAfter(now)) return Duration.between(now, candidate)
            }
            return Duration.ofDays(1)
        }

        fun DayOfWeek.iso(): Int = value
    }
}

/** The decision the worker makes, kept pure so it can be tested without WorkManager. */
object ReminderPolicy {
    /** Post only when reminders are on, the app may notify, and there is something unviewed to show. */
    fun shouldRemind(remindersEnabled: Boolean, notificationsAllowed: Boolean, unviewedConversations: Int): Boolean =
        remindersEnabled && notificationsAllowed && unviewedConversations > 0
}

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val inbox: InboxRepository,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val s = settings.current()
        // A locked or opening vault is not "nothing new": skip quietly and try again next time.
        val unviewed = try {
            inbox.unviewedConversationCount(s.reminderPackages)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
        if (ReminderPolicy.shouldRemind(s.remindersEnabled, canNotify(), unviewed)) post(unviewed)
        scheduler.rescheduleNow()
        return Result.success()
    }

    private fun canNotify(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun post(unviewed: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ReminderScheduler.CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ReminderScheduler.CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            android.app.PendingIntent.getActivity(context, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_quiet)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.resources.getQuantityString(R.plurals.reminder_body_count, unviewed, unviewed))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        // Checked here, in the same method as notify(): lint's MissingPermission is a hard error.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(REMINDER_ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: nothing to do, next run re-checks.
        }
    }

    companion object {
        private const val REMINDER_ID = 7_001
    }
}
