package com.flowdroid.di

import com.flowdroid.common.Clock
import com.flowdroid.common.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Top-level Hilt module owned by the app entry. Binds core abstractions whose implementations
 * live in `com.flowdroid.common` (and therefore aren't naturally owned by any feature module).
 *
 * Feature modules (DatabaseModule, LoggingModule, ServiceModule, PermissionModule, UI viewmodels)
 * bind their own interfaces. This module is intentionally small.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
