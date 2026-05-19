package com.flowdroid.common

/**
 * Shared health-state types. Used by services to report status and by UI to render the Health screen.
 * Pure data; no Android imports.
 */

enum class ServiceState {
    UNKNOWN,
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPED,
    DISABLED,   // user has explicitly disabled (e.g., in Settings)
    PERMISSION_MISSING,
}

data class HealthSnapshot(
    val foregroundServiceState: ServiceState,
    val notificationListenerState: ServiceState,
    val accessibilityServiceState: ServiceState,
    val notificationsBoundSinceMillis: Long?,
    val foregroundServiceUptimeMillis: Long,
    val lastListenerDisconnectMillis: Long?,
    val rebindAttemptsLast24h: Int,
    val notificationsReceivedLast24h: Int,
    val overallStatus: OverallStatus,
) {
    enum class OverallStatus { GREEN, AMBER, RED, UNKNOWN }
}

/** Reason a listener disconnect happened — used in health journal. */
enum class DisconnectReason {
    UNBIND,
    LISTENER_REMOVED,
    UNKNOWN,
}

/** What the watchdog did on a tick. Used for diagnostics. */
enum class WatchdogOutcome {
    ALL_OK,
    REBIND_REQUESTED,
    TOGGLED_COMPONENT,
    FOREGROUND_SERVICE_RESTARTED,
    PERMISSION_MISSING,
    ERROR,
}
