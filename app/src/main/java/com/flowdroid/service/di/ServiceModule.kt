package com.flowdroid.service.di

import com.flowdroid.common.service.ServiceController
import com.flowdroid.common.service.ServiceRegistry
import com.flowdroid.service.ServiceControllerImpl
import com.flowdroid.service.ServiceRegistryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the services module.
 *
 * [NotificationChannelManager] is constructor-injected with `@Inject`/`@Singleton` so it
 * does not need an entry here. Only the two interface-to-impl bindings are required.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds @Singleton
    abstract fun bindServiceRegistry(impl: ServiceRegistryImpl): ServiceRegistry

    @Binds @Singleton
    abstract fun bindServiceController(impl: ServiceControllerImpl): ServiceController
}
