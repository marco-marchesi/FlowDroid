package com.flowdroid.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.flowdroid.data.settings.ConnectionSettingsRepository
import com.flowdroid.data.settings.ConnectionSettingsRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideConnectionDataStore(
        @ApplicationContext ctx: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { ctx.preferencesDataStoreFile("connection_settings") },
    )

    @Provides
    @Singleton
    fun provideConnectionSettingsRepository(
        impl: ConnectionSettingsRepositoryImpl,
    ): ConnectionSettingsRepository = impl
}
