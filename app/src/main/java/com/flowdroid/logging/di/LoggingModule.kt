package com.flowdroid.logging.di

import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.TimberStructuredLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the logging subsystem.
 *
 *  - [StructuredLogger] is the public-facing logging API — every subsystem injects this. It is
 *    bound to [TimberStructuredLogger], which routes through Timber. The [com.flowdroid.logging.RoomLogTree]
 *    is planted by [com.flowdroid.logging.LogInitializer] at startup so all Timber output also lands in Room.
 *
 *  - [com.flowdroid.logging.RoomLogTree], [com.flowdroid.logging.LogInitializer], and
 *    [com.flowdroid.logging.CrashHandler] are constructor-injected `@Singleton`s; Hilt
 *    discovers them automatically without explicit bindings here.
 *
 * The module uses `abstract class` with `@Binds` because [Binds] is the cheaper, generated-code
 * form. A companion @Provides is included for the no-arg [TimberStructuredLogger] so Hilt knows
 * how to construct the binding target without us adding `@Inject` to a class in `common`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {

    @Binds
    @Singleton
    abstract fun bindStructuredLogger(impl: TimberStructuredLogger): StructuredLogger

    companion object {
        /**
         * [TimberStructuredLogger] lives in `:common` and has no `@Inject` constructor (it should
         * stay framework-agnostic). We provide it here so Hilt can satisfy [bindStructuredLogger].
         */
        @Provides
        @Singleton
        fun provideTimberStructuredLogger(): TimberStructuredLogger = TimberStructuredLogger()
    }
}
