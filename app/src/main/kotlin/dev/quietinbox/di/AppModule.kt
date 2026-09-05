package dev.quietinbox.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.quietinbox.feature.settings.ReminderScheduling
import dev.quietinbox.reminders.ReminderScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun bindReminderScheduling(impl: ReminderScheduler): ReminderScheduling
}
