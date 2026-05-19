package com.flowdroid.common.domain

/** Domain representation of one log entry, persisted into Room for the in-app log viewer. */
data class LogEntry(
    val id: Long = 0,
    val timestampMillis: Long,
    val level: Level,
    val tag: String,
    val message: String,
    val fieldsJson: String?,        // JSON-encoded structured fields
    val throwableClass: String?,
    val throwableMessage: String?,
    val stackTraceFirstLines: String?,    // first ~12 lines of the stack, for quick triage
) {
    enum class Level(val priority: Int) {
        DEBUG(3), INFO(4), WARN(5), ERROR(6);

        companion object {
            fun fromAndroidPriority(p: Int): Level = when {
                p <= 3 -> DEBUG
                p == 4 -> INFO
                p == 5 -> WARN
                else -> ERROR
            }
        }
    }
}

data class HealthEvent(
    val id: Long = 0,
    val timestampMillis: Long,
    val kind: Kind,
    val message: String,
    val outcome: String?,
) {
    enum class Kind {
        FOREGROUND_SERVICE_STARTED,
        FOREGROUND_SERVICE_STOPPED,
        LISTENER_CONNECTED,
        LISTENER_DISCONNECTED,
        LISTENER_REBIND_REQUESTED,
        ACCESSIBILITY_CONNECTED,
        ACCESSIBILITY_DISCONNECTED,
        WATCHDOG_TICK,
        BOOT_COMPLETED,
        PACKAGE_REPLACED,
        PERMISSION_CHANGED,
        SELF_TEST,
        CRASH,
    }
}
