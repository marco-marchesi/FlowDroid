package com.flowdroid.common

import timber.log.Timber

/**
 * Structured logger. Every call produces:
 *  - a `tag`: a short identifier of the subsystem ("NotifListener", "Watchdog", "Engine", etc.)
 *  - a `message`: human-readable summary
 *  - structured `fields`: key-value pairs that get encoded into the message AND persisted into Room
 *
 * This gives us logs that are scannable (grep `tag=Watchdog`) and machine-parseable (JSON-like
 * fields). When something fails, the field set makes the failure point obvious without reading code.
 *
 * Logging philosophy:
 *  - DEBUG: noisy traces useful in development. Disabled in release.
 *  - INFO: important state transitions ("listener bound", "service started", "watchdog ran").
 *  - WARN: degraded / recoverable conditions ("listener disconnect", "rebind requested").
 *  - ERROR: failed operations with a throwable. Always attach the cause.
 *
 * Never log secrets. The Room sink redacts known sensitive keys (see [REDACTED_KEYS]).
 *
 * Implementations are expected to be:
 *  - thread-safe
 *  - non-blocking (Room sink writes go through a buffered channel)
 *  - failure-tolerant (logger errors do not propagate)
 */
interface StructuredLogger {
    fun debug(tag: String, message: String, vararg fields: Pair<String, Any?>)
    fun info(tag: String, message: String, vararg fields: Pair<String, Any?>)
    fun warn(tag: String, message: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>)
    fun error(tag: String, message: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>)

    companion object {
        val REDACTED_KEYS = setOf(
            "password", "passwd", "secret", "token", "authorization",
            "auth", "apikey", "api_key", "x-flowdroid-secret",
        )
    }
}

/**
 * Convenience default: routes through Timber. The Room sink is plugged in as an additional
 * Timber.Tree by the [com.flowdroid.logging] module — keeping the in-memory logger simple.
 *
 * No `@Inject` constructor on purpose: this type lives in `common` and stays framework-agnostic.
 * Hilt binding is provided by [com.flowdroid.logging.di.LoggingModule].
 */
class TimberStructuredLogger : StructuredLogger {

    override fun debug(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        Timber.tag(tag).d(format(message, fields))

    override fun info(tag: String, message: String, vararg fields: Pair<String, Any?>) =
        Timber.tag(tag).i(format(message, fields))

    override fun warn(tag: String, message: String, throwable: Throwable?, vararg fields: Pair<String, Any?>) {
        val formatted = format(message, fields)
        if (throwable == null) Timber.tag(tag).w(formatted) else Timber.tag(tag).w(throwable, formatted)
    }

    override fun error(tag: String, message: String, throwable: Throwable?, vararg fields: Pair<String, Any?>) {
        val formatted = format(message, fields)
        if (throwable == null) Timber.tag(tag).e(formatted) else Timber.tag(tag).e(throwable, formatted)
    }

    private fun format(message: String, fields: Array<out Pair<String, Any?>>): String {
        if (fields.isEmpty()) return message
        val sb = StringBuilder(message).append(" {")
        fields.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(", ")
            val safe = if (k.lowercase() in StructuredLogger.REDACTED_KEYS) "***" else v.toString()
            sb.append(k).append('=').append(safe)
        }
        sb.append('}')
        return sb.toString()
    }
}
