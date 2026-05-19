package com.flowdroid.logging

import android.util.Log
import com.flowdroid.BuildConfig
import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.repo.LogRepository
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Timber [Timber.Tree] implementation that ALSO persists log entries to a [LogRepository]
 * for the in-app log viewer and crash reports.
 *
 * Design notes:
 *
 *  - The `log()` method runs on the calling thread (often the main thread). It MUST NOT block.
 *    We therefore offer each [LogEntry] into a non-blocking, drop-oldest [Channel] of capacity
 *    5000. A dedicated coroutine scope drains it in batches.
 *
 *  - We use [BufferOverflow.DROP_OLDEST] rather than DROP_LATEST: the most useful logs around a
 *    crash are the *most recent* ones. Older entries are more likely to already be persisted in
 *    a previous batch or simply less relevant.
 *
 *  - Batching: at most 100 entries per insert, flushed every 250ms or whenever 100 accumulate.
 *    This keeps Room's WAL writes amortized while bounding tail latency to a quarter-second.
 *
 *  - Failure tolerance: any throw from `insertAll` (Room corruption, disk-full, etc.) is caught,
 *    reported via [android.util.Log], and the consumer loop continues. Logging that crashes the
 *    app would be worse than missing logs.
 *
 *  - Self-healing: if the channel ever gets closed (shouldn't normally happen — capacity is
 *    fixed and we never close), we recreate the channel + scope on the next [log] call so the
 *    consumer comes back up.
 *
 *  - Verbose/Debug filtering: in release builds we don't persist DEBUG entries. They're too
 *    noisy and we don't want to fill user disk in production. INFO and above are always kept.
 *
 *  - Redaction: the [StructuredLogger] already redacts known sensitive keys at format time, but
 *    other code paths may have written `Authorization=Bearer xyz` directly. We do a best-effort
 *    regex pass on the persisted message.
 *
 *  - Test-friendly: the consumer [CoroutineScope] is a constructor parameter so tests can pass
 *    a [kotlinx.coroutines.test.TestScope] and drive virtual time deterministically.
 */
@Singleton
class RoomLogTree(
    private val logRepository: LogRepository,
    private val clock: Clock,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
    consumerScope: CoroutineScope? = null,
) : Timber.Tree() {

    @Inject
    constructor(
        logRepository: LogRepository,
        clock: Clock,
    ) : this(logRepository, clock, BuildConfig.DEBUG, consumerScope = null)

    // Our own scope unless the caller provided one — tests inject a TestScope.
    private val ownsScope: Boolean = consumerScope == null
    @Volatile
    private var scope: CoroutineScope = consumerScope ?: defaultScope()

    @Volatile
    private var channel: Channel<LogEntry> = newChannel()

    @Volatile
    private var consumerJob: Job? = null

    init {
        startConsumer()
    }

    private fun defaultScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RoomLogTree"))

    private fun newChannel(): Channel<LogEntry> =
        Channel(capacity = CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Timber entry point. Called from the thread that originated the log call. Must not block.
     *
     * We swallow every exception. If anything goes wrong here, the app keeps running.
     */
    public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        try {
            // Filter low-priority logs in release builds — Timber may still print them, but we
            // don't pay for Room writes.
            if (priority <= Log.DEBUG && !isDebugBuild) return

            val entry = buildEntry(priority, tag, message, t)
            offer(entry)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            // Logging must never crash. Best-effort report to logcat.
            Log.e(LOGCAT_TAG, "RoomLogTree.log failed", e)
        }
    }

    /**
     * Offer the entry to the channel without blocking. If the channel was closed for any reason
     * (shouldn't happen — we never close ours, but a test could), rebuild it and retry once.
     */
    private fun offer(entry: LogEntry) {
        val ch = channel
        val result = ch.trySend(entry)
        if (result.isClosed) {
            // Self-heal: rebuild channel + restart consumer, then retry once.
            synchronized(this) {
                if (channel === ch) {
                    if (ownsScope && !scope.isActive) scope = defaultScope()
                    channel = newChannel()
                    startConsumer()
                }
            }
            try {
                channel.trySend(entry)
            } catch (_: ClosedSendChannelException) {
                // Give up. We've done what we can.
            }
        }
        // result.isFailure (capacity full) is handled by DROP_OLDEST automatically. No action.
    }

    /**
     * Build an immutable [LogEntry] from Timber inputs. This runs on the caller's thread, so
     * we keep the work cheap: a single regex pass on the message and a slice of the stack.
     */
    private fun buildEntry(priority: Int, tag: String?, message: String, t: Throwable?): LogEntry {
        val redactedMessage = redact(message)
        return LogEntry(
            timestampMillis = clock.nowMillis(),
            level = LogEntry.Level.fromAndroidPriority(priority),
            tag = tag ?: DEFAULT_TAG,
            message = redactedMessage,
            fieldsJson = null,
            throwableClass = t?.javaClass?.simpleName,
            throwableMessage = t?.message?.take(MAX_THROWABLE_MESSAGE_CHARS),
            stackTraceFirstLines = t?.let(::firstStackLines),
        )
    }

    /**
     * Start (or restart) the consumer coroutine. Idempotent: if a job is already active we
     * don't spawn another.
     */
    private fun startConsumer() {
        synchronized(this) {
            val existing = consumerJob
            if (existing != null && existing.isActive) return
            consumerJob = scope.launch { consume() }
        }
    }

    /**
     * The drain loop. Pulls from the channel into a local buffer of up to [BATCH_SIZE], flushing
     * whenever the buffer fills or [FLUSH_INTERVAL_MS] elapses since the first buffered entry.
     */
    private suspend fun consume() {
        val buffer = ArrayList<LogEntry>(BATCH_SIZE)
        while (scope.isActive) {
            try {
                // Wait for the first entry — no timeout, the loop is event-driven.
                val first = channel.receive()
                buffer.add(first)

                // Drain up to BATCH_SIZE more entries, or until FLUSH_INTERVAL_MS elapses.
                // [withTimeoutOrNull] uses the coroutine scheduler's clock, which means this
                // works correctly under both real time and `TestScope` virtual time. Cancelling
                // the inner block at the timeout is the normal path — we just flush what we have.
                withTimeoutOrNull(FLUSH_INTERVAL_MS) {
                    while (buffer.size < BATCH_SIZE) {
                        buffer.add(channel.receive())
                    }
                }

                flush(buffer)
                buffer.clear()
            } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                // Never let the consumer die from a transient error.
                Log.e(LOGCAT_TAG, "RoomLogTree consumer loop error", e)
                buffer.clear()
            }
        }
    }

    private suspend fun flush(buffer: List<LogEntry>) {
        if (buffer.isEmpty()) return
        try {
            val outcome = logRepository.insertAll(buffer)
            if (outcome is Outcome.Err) {
                Log.w(LOGCAT_TAG, "Batch insert failed: ${outcome.error} (${buffer.size} entries dropped)")
            }
        } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            // Repo threw rather than returning Err — should not happen, but defend anyway.
            Log.e(LOGCAT_TAG, "Batch insert threw (${buffer.size} entries dropped)", e)
        }
    }

    private fun firstStackLines(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString().lineSequence().take(STACK_LINES).joinToString("\n")
    }

    /**
     * Best-effort regex redaction of `key=value` pairs whose key matches a known sensitive key
     * (case-insensitive). Replaces the value with `***`. Designed to be cheap — we use a single
     * compiled regex.
     */
    private fun redact(message: String): String {
        if (REDACTION_KEYWORDS.none { message.contains(it, ignoreCase = true) }) return message
        return REDACTION_REGEX.replace(message) { match ->
            val key = match.groupValues[1]
            "$key=***"
        }
    }

    companion object {
        const val CHANNEL_CAPACITY = 5000
        const val BATCH_SIZE = 100
        const val FLUSH_INTERVAL_MS = 250L
        const val POLL_INTERVAL_MS = 10L
        const val MAX_THROWABLE_MESSAGE_CHARS = 500
        const val STACK_LINES = 12
        const val DEFAULT_TAG = "App"
        private const val LOGCAT_TAG = "RoomLogTree"

        // Precomputed lowercase keyword list for fast pre-check before regex.
        private val REDACTION_KEYWORDS: List<String> =
            StructuredLogger.REDACTED_KEYS.map { it.lowercase() }

        // Matches `<key>=<value>` where value runs until whitespace, comma, or closing brace.
        // We capture the key verbatim (preserving its case) and rewrite the value.
        private val REDACTION_REGEX: Regex = run {
            val alternation = StructuredLogger.REDACTED_KEYS.joinToString("|") { Regex.escape(it) }
            Regex("(?i)\\b($alternation)\\s*=\\s*([^\\s,}]+)")
        }
    }
}
