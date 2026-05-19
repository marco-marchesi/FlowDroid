package com.flowdroid.engine.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Single [OkHttpClient] instance shared across all HTTP actions in the app.
 *
 * Defaults are conservative — per-action timeouts in [com.flowdroid.engine.actions.HttpActionExecutor]
 * tighten further via `newBuilder().callTimeout(...)`. Sharing one client matters for
 * connection-pool reuse: each new client allocates an `ExecutorService` and a `ConnectionPool`,
 * so spawning per-call is expensive.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
