package com.flowdroid

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.flowdroid.common.service.ServiceController
import com.flowdroid.logging.CrashHandler
import com.flowdroid.logging.LogInitializer
import com.flowdroid.platform.lifecycle.TriggerLifecycle
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * App entry. Wires:
 *  - Hilt
 *  - Timber + Room log sink (via [LogInitializer])
 *  - global uncaught exception handler (via [CrashHandler])
 *  - WorkManager with the Hilt-aware factory
 *  - Starts the foreground service on cold start so the listener gets a chance to (re)bind quickly
 *
 * Order matters: logger first, then crash handler (so crashes can write to Room),
 * then the rest. If any later step throws, the crash handler will capture it.
 */
@HiltAndroidApp
class FlowDroidApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logInitializer: LogInitializer
    @Inject lateinit var crashHandler: CrashHandler
    @Inject lateinit var serviceController: ServiceController
    @Inject lateinit var triggerLifecycle: TriggerLifecycle

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // 1. Logging — must come first so subsequent failures are captured.
        runCatchingNoCancel("LogInitializer.install") { logInitializer.install() }

        // 2. Crash handler — captures the last N log entries on uncaught exception.
        runCatchingNoCancel("CrashHandler.install") { crashHandler.install() }

        // 3. Start the orchestrator (idempotent — calling repeatedly is safe).
        runCatchingNoCancel("ServiceController.bootstrap") { serviceController.bootstrap() }

        // 4. Start the trigger lifecycle subscription so non-notification triggers
        //    (TimeOfDay, Interval, Webhook) get armed/disarmed in lockstep with flow state.
        runCatchingNoCancel("TriggerLifecycle.start") { triggerLifecycle.start() }
    }

    /**
     * Runs [block], catching any exception and logging it without crashing the app. Used during
     * onCreate where a single subsystem failing must not poison the whole app.
     *
     * Re-throws coroutine cancellation and VM errors per [com.flowdroid.common.outcomeCatching].
     */
    private inline fun runCatchingNoCancel(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            // Logger may itself have failed during step 1 — fall back to system log.
            android.util.Log.e("FlowDroidApplication", "onCreate step '$label' failed", t)
        }
    }
}
