package com.flowdroid.service

import com.flowdroid.common.Clock
import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.ServiceState
import com.flowdroid.common.WatchdogOutcome

/**
 * Pure-Kotlin decision logic for [WatchdogWorker], factored out so it is testable
 * on the JVM without WorkManager / Android dependencies.
 *
 * The escalation ladder (in order):
 *  1. If the listener component is reported as PERMISSION_MISSING / DISABLED, return
 *     [WatchdogOutcome.PERMISSION_MISSING] — only the user can fix this; we surface
 *     a HIGH-importance alert and stop trying to rebind.
 *  2. If the foreground service is STOPPED or has been UNKNOWN for more than [BOOT_GRACE_MILLIS],
 *     restart it. (UNKNOWN is normal in the first ~2 minutes after a cold boot.)
 *  3. If the listener is DEGRADED *and* has accumulated >= [DEGRADED_TOGGLE_THRESHOLD]
 *     rebind attempts in the last 24h, escalate to a component toggle — Samsung sometimes
 *     refuses to honour `requestRebind` and only a disable/enable cycle wakes it up.
 *  4. Otherwise if the listener is anything but RUNNING/DISABLED, request a normal rebind.
 *  5. All green — record a tick.
 */
internal object WatchdogLogic {

    /** How long after the first watchdog tick we allow [ServiceState.UNKNOWN] before forcing a restart. */
    const val BOOT_GRACE_MILLIS = 2L * 60L * 1000L

    /** Number of rebind attempts in 24h after which we escalate to a component toggle. */
    const val DEGRADED_TOGGLE_THRESHOLD = 2

    /**
     * Decide what the watchdog should do this tick.
     *
     * @param snapshot current registry snapshot
     * @param clock used to evaluate the boot grace window
     * @param processStartElapsed elapsedRealtime at which this process started — used to know
     *     whether we're still in the boot grace window. Pass `null` if unknown (treated as
     *     "long ago" so we will restart aggressively).
     */
    fun decideWatchdogAction(
        snapshot: HealthSnapshot,
        clock: Clock,
        processStartElapsed: Long? = null,
    ): WatchdogOutcome {
        // 1. User-driven loss of access — only escalation is to alert the user.
        if (snapshot.notificationListenerState == ServiceState.PERMISSION_MISSING) {
            return WatchdogOutcome.PERMISSION_MISSING
        }

        // 2. Foreground service down or degraded — restart (unless still in the boot grace window).
        //    DEGRADED means the FGS heartbeat loop or adaptive-notification observer is unhealthy;
        //    neither a rebind request nor a component toggle would fix that — only a full restart.
        val fg = snapshot.foregroundServiceState
        val inBootGrace = processStartElapsed?.let {
            clock.elapsedRealtime() - it < BOOT_GRACE_MILLIS
        } ?: false
        if (fg == ServiceState.STOPPED) {
            return WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED
        }
        if (fg == ServiceState.DEGRADED) {
            return WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED
        }
        if (fg == ServiceState.UNKNOWN && !inBootGrace) {
            return WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED
        }

        // 3. Listener degraded with repeated rebinds → toggle component.
        val listener = snapshot.notificationListenerState
        if (listener == ServiceState.DEGRADED &&
            snapshot.rebindAttemptsLast24h >= DEGRADED_TOGGLE_THRESHOLD
        ) {
            return WatchdogOutcome.TOGGLED_COMPONENT
        }

        // 4. Listener not bound and not user-disabled → ordinary rebind.
        if (listener != ServiceState.RUNNING &&
            listener != ServiceState.DISABLED &&
            listener != ServiceState.UNKNOWN
        ) {
            return WatchdogOutcome.REBIND_REQUESTED
        }

        // 5. All green.
        return WatchdogOutcome.ALL_OK
    }
}
