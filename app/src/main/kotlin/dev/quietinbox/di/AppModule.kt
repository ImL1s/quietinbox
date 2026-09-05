package dev.quietinbox.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.quietinbox.BuildConfig
import dev.quietinbox.core.model.BuildInfo
import dev.quietinbox.feature.settings.ReminderScheduling
import dev.quietinbox.reminders.ReminderScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun bindReminderScheduling(impl: ReminderScheduler): ReminderScheduling

    companion object {
        /**
         * The one place that reads the generated `BuildConfig`, so feature modules can gate
         * developer-only affordances without depending on the app module. There are no product
         * flavors yet, hence the empty flavor name.
         */
        @Provides
        @Singleton
        fun provideBuildInfo(): BuildInfo = BuildInfo(debug = BuildConfig.DEBUG, flavor = "")
    }
}
