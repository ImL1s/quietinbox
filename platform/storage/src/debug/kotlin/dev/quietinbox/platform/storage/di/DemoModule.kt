package dev.quietinbox.platform.storage.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.quietinbox.platform.storage.repo.DemoData
import dev.quietinbox.platform.storage.repo.DemoDataRepository

/** Debug builds: the real seeder. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DemoModule {
    @Binds
    abstract fun bindDemoData(impl: DemoDataRepository): DemoData
}
