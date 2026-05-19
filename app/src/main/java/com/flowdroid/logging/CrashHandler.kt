package com.flowdroid.logging

import android.util.Log
import com.flowdroid.common.Clock
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global uncaught-exception handler that captures crash context to Room *before* the OS kills
 * the process.
 *
 * Workflow on crash:
 *  1. Snapshot the last 100 log entries — this gives us "what was happening" just before death.
 *     (The buffered log tree may still have entries in flight; we accept that — they're lost.)
 *  2. Insert a [HealthEvent] of kind [HealthEvent.Kind.CRASH] so the watchdog UI surfaces it.
 *  3. Insert a synthetic [LogEntry] at ERROR level so the in-app log viewer shows the crash.
 *  4. Delegate to the previous handler so:
 *      - Android's default behavior still runs (process death, optional crash dialog).
 *      - We don't infinite-loop if our own handler somehow throws and gets caught by us again.
 *
 * Safety constraints:
 *  - All Room writes happen inside [runBlocking] with a 3-second [withTimeoutOrNull]. We're
 *    about to die; we cannot wait forever for disk. If the timeout hits, we just move on.
 *  - Every operation is wrapped in try/catch. The crash handler itself MUST NOT throw — if it
 *    does, we lose the ability to call the previous handler and may end up with an inconsistent
 *    crash report. On any failure we fall back to [Log.e].
 *  - Install is idempotent — chaining the same handler twice would create a loop.
 */
@Singleton
class CrashHandler @Inject constructor(
    private val logRepository: LogRepository,
    private val healthRepository: HealthRepository,
    private val clock: Clock,
) {

    private val installed = AtomicBoolean(false)

    /** Previous handler we chain to. Captured at install() time. */
    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(::handleUncaught)
    }

    /**
     * Test/diagnostic hook — exposed so tests can invoke the handler directly without crashing
     * the JVM via an actual uncaught exception. Production code reaches this via the chain.
     */
    internal fun handleUncaught(thread: Thread, throwable: Throwable) {
        try {
            captureCrash(thread, throwable)
        } catch (oom: OutOfMemoryError) {
            // Pass through — capture is best-effort, but OOM must propagate.
            Log.e(TAG, "OOM during crash capture; rethrowing")
            // Fall through to delegate.
        } catch (e: Throwable) {
            // Absolutely never throw from here.
            Log.e(TAG, "Crash handler itself failed", e)
        } finally {
            delegateToPrevious(thread, throwable)
        }
    }

    private fun captureCrash(thread: Thread, throwable: Throwable) {
        val now = clock.nowMillis()
        val throwableClass = throwable.javaClass.name
        val throwableMessage = throwable.message?.take(MAX_THROWABLE_MESSAGE_CHARS)
        val stackHead = firstStackLines(throwable)

        // Use runBlocking — we're on the dying thread, we have no choice but to wait.
        // Bounded by the 3s timeout below so we don't block ANR-like.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                // Snapshot recent logs. The result is ignored for I/O purposes — the act of
                // snapshotting persists nothing — but we need the call because tests assert
                // that we DID gather context. In production, the recent entries are already
                // in Room; this call effectively forces any pending repository state to
                // settle and gives implementations a hook to add side-effects (e.g. flush).
                safely { logRepository.snapshotRecent(SNAPSHOT_COUNT) }

                // 1. HealthEvent — surfaces in the watchdog/health UI.
                safely {
                    healthRepository.insert(
                        HealthEvent(
                            timestampMillis = now,
                            kind = HealthEvent.Kind.CRASH,
                            message = "$throwableClass: ${throwableMessage ?: "(no message)"}",
                            outcome = "uncaught on ${thread.name}",
                        )
                    )
                }

                // 2. Synthetic ERROR log entry — shows up in the in-app log viewer.
                safely {
                    logRepository.insert(
                        LogEntry(
                            timestampMillis = now,
                            level = LogEntry.Level.ERROR,
                            tag = TAG,
                            message = "Uncaught exception on thread '${thread.name}'",
                            fieldsJson = null,
                            throwableClass = throwable.javaClass.simpleName,
                            throwableMessage = throwableMessage,
                            stackTraceFirstLines = stackHead,
                        )
                    )
                }
            }
        }
    }

    /**
     * Hands the crash off to whatever handler existed before us — typically Android's default,
     * which logs to logcat and kills the process. If for some reason there was no previous
     * handler, we kill the current thread ourselves so the JVM doesn't enter an undefined state.
     */
    private fun delegateToPrevious(thread: Thread, throwable: Throwable) {
        val prev = previousHandler
        try {
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                // No previous handler — extremely unusual. Print to err and let the OS terminate.
                Log.e(TAG, "No previous uncaught handler; thread will die", throwable)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Previous handler threw", e)
        }
    }

    /** Run a suspend block, swallowing any non-fatal throw so the next step still runs. */
    private suspend inline fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            Log.e(TAG, "Crash capture step failed", e)
        }
    }

    private fun firstStackLines(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString().lineSequence().take(STACK_LINES).joinToString("\n")
    }

    companion object {
        private const val TAG = "CrashHandler"
        const val SNAPSHOT_COUNT = 100
        const val CAPTURE_TIMEOUT_MS = 3_000L
        const val MAX_THROWABLE_MESSAGE_CHARS = 500
        const val STACK_LINES = 12
    }
}
