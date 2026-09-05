package dev.quietinbox.platform.storage.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.quietinbox.platform.storage.repo.DemoData
import dev.quietinbox.platform.storage.repo.NoDemoData

/** Release builds: no demo content, no seeder. */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    @Provides
    fun demoData(): DemoData = NoDemoData
}
