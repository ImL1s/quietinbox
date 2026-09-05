package dev.quietinbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.quietinbox.platform.storage.retention.RetentionService
import dev.quietinbox.reminders.ReminderScheduler
import javax.inject.Inject

@HiltAndroidApp
class QuietInboxApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var reminders: ReminderScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        RetentionService.schedule(this)
        reminders.reschedule()
    }
}
