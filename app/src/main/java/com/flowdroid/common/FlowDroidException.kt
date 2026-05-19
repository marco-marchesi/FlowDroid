package com.flowdroid.common

/**
 * Root of the FlowDroid exception hierarchy.
 *
 * Use exceptions ONLY for:
 *  - invariant violations / programmer errors (`IllegalStateException`-equivalents)
 *  - unrecoverable system failures we want surfaced to the crash handler
 *  - rethrows of third-party exceptions we have no typed answer for
 *
 * For *expected* failures, use [Outcome] with a typed error instead.
 *
 * Every subclass carries:
 *  - a `code`: short stable identifier used in logs and crash reports
 *  - a `userMessage`: optional message safe to show in UI (no stack traces or PII)
 *
 * When throwing, always include enough context in [message] to identify the failing operation
 * without reading the surrounding code. Bad: "failed". Good: "PostNotification failed: channelId=null".
 */
sealed class FlowDroidException(
    val code: String,
    message: String,
    cause: Throwable? = null,
    val userMessage: String? = null,
) : RuntimeException(message, cause) {

    /** Configuration is broken — programmer error, not user error. */
    class Configuration(
        message: String,
        cause: Throwable? = null,
    ) : FlowDroidException("CONFIG", message, cause)

    /** A required Android permission is missing at runtime. Recoverable: ask the user. */
    class PermissionMissing(
        val permission: String,
        cause: Throwable? = null,
    ) : FlowDroidException(
        code = "PERM_MISSING",
        message = "Permission missing: $permission",
        cause = cause,
        userMessage = "FlowDroid needs the $permission permission to perform this action.",
    )

    /** Android system service unavailable or threw. */
    class SystemService(
        val service: String,
        message: String,
        cause: Throwable? = null,
    ) : FlowDroidException(
        code = "SYS_SERVICE",
        message = "System service $service failed: $message",
        cause = cause,
    )

    /** Persistence (Room / DataStore / files) failure. */
    class Persistence(
        message: String,
        cause: Throwable? = null,
    ) : FlowDroidException("PERSIST", message, cause)

    /** Background service was supposed to be running but isn't. */
    class ServiceNotRunning(
        val serviceName: String,
        cause: Throwable? = null,
    ) : FlowDroidException(
        code = "SVC_DOWN",
        message = "Service $serviceName is not running.",
        cause = cause,
    )

    /** A flow execution hit an unrecoverable internal error. */
    class Engine(
        val flowId: String,
        message: String,
        cause: Throwable? = null,
    ) : FlowDroidException(
        code = "ENGINE",
        message = "Flow $flowId engine error: $message",
        cause = cause,
    )

    /** Catch-all for anything not yet typed. Prefer adding a new subclass over using this. */
    class Unexpected(
        message: String,
        cause: Throwable? = null,
    ) : FlowDroidException("UNEXPECTED", message, cause)
}
