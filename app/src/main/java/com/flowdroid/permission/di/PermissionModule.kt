package com.flowdroid.permission.di

import com.flowdroid.common.permission.OemBatteryHelper
import com.flowdroid.common.permission.PermissionChecker
import com.flowdroid.permission.OemBatteryHelperImpl
import com.flowdroid.permission.PermissionCheckerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the permissions subsystem.
 *
 *  - [PermissionChecker] → [PermissionCheckerImpl] (singleton; backs a StateFlow used app-wide).
 *  - [OemBatteryHelper]  → [OemBatteryHelperImpl] (singleton; pure deep-link resolution).
 *
 * [com.flowdroid.permission.OemDetector] is constructor-injected `@Singleton` and discovered
 * automatically by Hilt — no explicit binding required.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionModule {

    @Binds
    @Singleton
    abstract fun bindPermissionChecker(impl: PermissionCheckerImpl): PermissionChecker

    @Binds
    @Singleton
    abstract fun bindOemBatteryHelper(impl: OemBatteryHelperImpl): OemBatteryHelper
}
