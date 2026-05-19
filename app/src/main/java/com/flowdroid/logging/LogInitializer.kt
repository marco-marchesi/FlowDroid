package com.flowdroid.logging

import com.flowdroid.BuildConfig
import com.flowdroid.common.Clock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires up the logging stack at application startup.
 *
 * Responsibilities:
 *  - Plant [Timber.DebugTree] only in DEBUG builds (logcat output).
 *  - Plant [RoomLogTree] in every build (in-app log viewer + crash bundle).
 *  - Emit a marker INFO entry so we can confirm the pipeline is alive end-to-end.
 *
 * Idempotency:
 *  - [install] is guarded by an [AtomicBoolean]. A second call is a no-op, both because
 *    [FlowDroidApplication] guarantees a single call but also because a misconfigured test
 *    or a process restart in unusual scenarios could call it twice — and planting trees twice
 *    leads to duplicate logs.
 *
 * The [Clock] is injected (not used directly here) to express the dependency the rest of
 * the logging stack has on it and to keep the constructor explicit for tests.
 */
@Singleton
class LogInitializer @Inject constructor(
    private val roomLogTree: RoomLogTree,
    @Suppress("unused") private val clock: Clock,
) {

    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return

        if (BuildConfig.DEBUG) {
            // DebugTree prefixes with class+line, which is invaluable when reading logcat.
            Timber.plant(Timber.DebugTree())
        }

        // The Room tree is always planted — it powers the in-app log viewer and the crash bundle.
        Timber.plant(roomLogTree)

        // Marker so we can confirm in the log viewer that initialization actually ran. Tag
        // matches the subsystem name pattern used by StructuredLogger.
        Timber.tag(TAG).i("Logging initialized")
    }

    companion object {
        private const val TAG = "Logging"
    }
}
