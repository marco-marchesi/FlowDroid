package com.flowdroid.data.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.InstalledPackagesRepository
import com.flowdroid.common.repo.FlowRunRepository
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.LogRepository
import com.flowdroid.common.repo.NotificationRepository
import com.flowdroid.data.db.FlowDao
import com.flowdroid.data.db.FlowDroidDatabase
import com.flowdroid.data.db.FlowRunDao
import com.flowdroid.data.db.HealthEventDao
import com.flowdroid.data.db.LogEntryDao
import com.flowdroid.data.db.NotificationEventDao
import com.flowdroid.data.repo.FlowRepositoryImpl
import com.flowdroid.data.repo.FlowRunRepositoryImpl
import com.flowdroid.data.repo.HealthRepositoryImpl
import com.flowdroid.data.repo.InstalledPackagesRepositoryImpl
import com.flowdroid.data.repo.LogRepositoryImpl
import com.flowdroid.data.repo.NotificationRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module wiring the data layer:
 *
 *  1. [provideDatabase] builds a single Room database instance for the whole app.
 *  2. The DAO `@Provides` extract DAOs from the database — Hilt does not auto-derive these.
 *  3. The nested [Bindings] abstract module binds repository interfaces (declared in
 *     `com.flowdroid.common.repo`) to their data-layer implementations.
 *
 * Migration policy:
 *  - `fallbackToDestructiveMigrationOnDowngrade()` — if a newer schema is somehow installed and
 *    we revert (e.g. install an older APK), wipe the DB. Acceptable because the data here is
 *    rolling-window (notifications, logs, health). Flow definitions in later phases will need a
 *    different strategy.
 *  - We deliberately do NOT enable destructive migration on upgrade. Migrations must be written
 *    explicitly; an unhandled upgrade should crash so we catch it in CI before shipping.
 *
 * The [RoomDatabase.Callback] uses `android.util.Log` directly because Timber / StructuredLogger
 * may not be initialised at the precise moment Room opens the DB (DI graph still being assembled).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val LOG_TAG = "FlowDroidDb"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): FlowDroidDatabase {
        return Room.databaseBuilder(
            context,
            FlowDroidDatabase::class.java,
            FlowDroidDatabase.NAME,
        )
            .addMigrations(
                FlowDroidDatabase.MIGRATION_1_2,
                FlowDroidDatabase.MIGRATION_2_3,
                FlowDroidDatabase.MIGRATION_3_4,
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .addCallback(LoggingCallback)
            .build()
    }

    @Provides
    @Singleton
    fun provideNotificationEventDao(db: FlowDroidDatabase): NotificationEventDao =
        db.notificationEventDao()

    @Provides
    @Singleton
    fun provideLogEntryDao(db: FlowDroidDatabase): LogEntryDao = db.logEntryDao()

    @Provides
    @Singleton
    fun provideHealthEventDao(db: FlowDroidDatabase): HealthEventDao = db.healthEventDao()

    @Provides
    @Singleton
    fun provideFlowDao(db: FlowDroidDatabase): FlowDao = db.flowDao()

    @Provides
    @Singleton
    fun provideFlowRunDao(db: FlowDroidDatabase): FlowRunDao = db.flowRunDao()

    /**
     * Tiny callback that records DB lifecycle events to logcat. Useful when diagnosing "why is
     * the DB empty?" or "why was a migration triggered?" in bug reports.
     */
    private object LoggingCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            Log.i(LOG_TAG, "FlowDroidDatabase onCreate version=${db.version}")
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            Log.i(LOG_TAG, "FlowDroidDatabase onOpen version=${db.version}")
        }
    }

    /**
     * Repository binding sub-module. `@Binds` is preferred over `@Provides` for simple
     * interface-to-impl wiring because Hilt can generate the factory without reflection at
     * runtime.
     */
    @Module
    @InstallIn(SingletonComponent::class)
    abstract class Bindings {

        @Binds
        @Singleton
        abstract fun bindNotificationRepository(
            impl: NotificationRepositoryImpl,
        ): NotificationRepository

        @Binds
        @Singleton
        abstract fun bindLogRepository(impl: LogRepositoryImpl): LogRepository

        @Binds
        @Singleton
        abstract fun bindHealthRepository(impl: HealthRepositoryImpl): HealthRepository

        @Binds
        @Singleton
        abstract fun bindFlowRepository(impl: FlowRepositoryImpl): FlowRepository

        @Binds
        @Singleton
        abstract fun bindFlowRunRepository(impl: FlowRunRepositoryImpl): FlowRunRepository

        @Binds
        @Singleton
        abstract fun bindInstalledPackagesRepository(
            impl: InstalledPackagesRepositoryImpl,
        ): InstalledPackagesRepository

        // NOTE: Clock binding lives in `com.flowdroid.di.AppModule` (a more semantically-neutral
        // location). Do not re-add it here or Hilt will fail with a duplicate-binding error.
    }
}
