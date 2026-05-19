package com.flowdroid.service

import com.flowdroid.common.Clock
import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.HealthSnapshot.OverallStatus
import com.flowdroid.common.ServiceState
import com.flowdroid.common.service.ServiceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, thread-safe implementation of [ServiceRegistry].
 *
 * Threading model:
 *  - All mutation goes through `synchronized(lock)` blocks. The blocks are tiny (just list
 *    appends and snapshot rebuilds) so contention is irrelevant in practice.
 *  - The [MutableStateFlow] is updated *after* the internal state has been recomputed.
 *    Subscribers therefore always see a consistent snapshot.
 *
 * Bounded history:
 *  - Both `listenerDisconnects` and `rebindAttempts` are capped at [MAX_JOURNAL] entries.
 *    On a misbehaving device the watchdog could in principle generate thousands of disconnect
 *    events per day; an unbounded list would slowly OOM us. 256 is enough for a week of
 *    every-15-minute disconnects and is also enough to compute the "last 24h" stat trivially.
 *
 * Notifications-received counter:
 *  - Stored as a circular buffer of post timestamps so we can compute the 24h rolling count
 *    on demand without timers. The buffer is sized at 4096 (~one every 21s for 24h) which
 *    covers any realistic notification load; older entries beyond the window are dropped.
 */
@Singleton
class ServiceRegistryImpl @Inject constructor(
    private val clock: Clock,
) : ServiceRegistry {

    private val lock = Any()

    // Mutable fields — all guarded by [lock].
    private var foregroundState: ServiceState = ServiceState.UNKNOWN
    private var listenerState: ServiceState = ServiceState.UNKNOWN
    private var accessibilityState: ServiceState = ServiceState.UNKNOWN
    private var listenerBoundSinceMillis: Long? = null
    private var foregroundStartedElapsed: Long? = null

    private val listenerDisconnects: ArrayDeque<Long> = ArrayDeque()
    private val rebindAttempts: ArrayDeque<Long> = ArrayDeque()
    private val notificationsReceived: ArrayDeque<Long> = ArrayDeque()

    private val snapshotFlow = MutableStateFlow(initialSnapshot())

    override fun observeSnapshot(): Flow<HealthSnapshot> = snapshotFlow.asStateFlow()

    override fun snapshot(): HealthSnapshot = snapshotFlow.value

    override fun setForegroundServiceState(state: ServiceState) {
        synchronized(lock) {
            foregroundState = state
            // Track uptime starting at the first RUNNING transition since boot.
            when (state) {
                ServiceState.RUNNING -> if (foregroundStartedElapsed == null) {
                    foregroundStartedElapsed = clock.elapsedRealtime()
                }
                ServiceState.STOPPED, ServiceState.UNKNOWN -> foregroundStartedElapsed = null
                else -> Unit
            }
            publish()
        }
    }

    override fun setNotificationListenerState(state: ServiceState, sinceMillis: Long?) {
        synchronized(lock) {
            listenerState = state
            if (state == ServiceState.RUNNING) {
                listenerBoundSinceMillis = sinceMillis ?: clock.nowMillis()
            } else if (state == ServiceState.STOPPED || state == ServiceState.DISABLED) {
                listenerBoundSinceMillis = null
            }
            publish()
        }
    }

    override fun setAccessibilityServiceState(state: ServiceState) {
        synchronized(lock) {
            accessibilityState = state
            publish()
        }
    }

    override fun recordListenerDisconnect(timestampMillis: Long) {
        synchronized(lock) {
            listenerDisconnects.addLast(timestampMillis)
            while (listenerDisconnects.size > MAX_JOURNAL) listenerDisconnects.removeFirst()
            publish()
        }
    }

    override fun recordRebindAttempt(timestampMillis: Long) {
        synchronized(lock) {
            rebindAttempts.addLast(timestampMillis)
            while (rebindAttempts.size > MAX_JOURNAL) rebindAttempts.removeFirst()
            publish()
        }
    }

    override fun incrementNotificationReceived() {
        synchronized(lock) {
            val now = clock.nowMillis()
            notificationsReceived.addLast(now)
            // Drop timestamps older than 24h so the deque size stays bounded.
            val cutoff = now - DAY_MILLIS
            while (notificationsReceived.isNotEmpty() && notificationsReceived.first() < cutoff) {
                notificationsReceived.removeFirst()
            }
            // Hard cap regardless — pathological cases.
            while (notificationsReceived.size > MAX_NOTIF_BUFFER) notificationsReceived.removeFirst()
            publish()
        }
    }

    /** Recompute the [HealthSnapshot] and publish it. Must be called with [lock] held. */
    private fun publish() {
        snapshotFlow.value = buildSnapshot()
    }

    private fun initialSnapshot(): HealthSnapshot = HealthSnapshot(
        foregroundServiceState = ServiceState.UNKNOWN,
        notificationListenerState = ServiceState.UNKNOWN,
        accessibilityServiceState = ServiceState.UNKNOWN,
        notificationsBoundSinceMillis = null,
        foregroundServiceUptimeMillis = 0L,
        lastListenerDisconnectMillis = null,
        rebindAttemptsLast24h = 0,
        notificationsReceivedLast24h = 0,
        overallStatus = OverallStatus.UNKNOWN,
    )

    private fun buildSnapshot(): HealthSnapshot {
        val nowWall = clock.nowMillis()
        val nowElapsed = clock.elapsedRealtime()
        val cutoff = nowWall - DAY_MILLIS

        val rebindsLast24h = rebindAttempts.count { it >= cutoff }
        val notifLast24h = notificationsReceived.count { it >= cutoff }
        val uptime = foregroundStartedElapsed?.let { nowElapsed - it } ?: 0L

        return HealthSnapshot(
            foregroundServiceState = foregroundState,
            notificationListenerState = listenerState,
            accessibilityServiceState = accessibilityState,
            notificationsBoundSinceMillis = listenerBoundSinceMillis,
            foregroundServiceUptimeMillis = uptime,
            lastListenerDisconnectMillis = listenerDisconnects.lastOrNull(),
            rebindAttemptsLast24h = rebindsLast24h,
            notificationsReceivedLast24h = notifLast24h,
            overallStatus = computeOverall(foregroundState, listenerState),
        )
    }

    companion object {
        /** Max entries kept in the disconnect / rebind journals. */
        const val MAX_JOURNAL = 256

        /** Defensive hard cap on the notifications-received buffer. */
        const val MAX_NOTIF_BUFFER = 4096

        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /**
         * Pure status function — extracted as `internal` so [WatchdogLogic] and unit tests
         * can reuse it without going through the registry.
         *
         * Rules (per PLAN.md §4.1):
         *  - GREEN: FGS RUNNING AND listener is RUNNING (or explicitly DISABLED — user choice).
         *  - AMBER: anything DEGRADED or PERMISSION_MISSING but listener still bound.
         *  - RED:   listener UNBOUND/STOPPED, or FGS STOPPED.
         *  - UNKNOWN at startup until any real state has been recorded.
         */
        internal fun computeOverall(fg: ServiceState, listener: ServiceState): OverallStatus {
            if (fg == ServiceState.UNKNOWN && listener == ServiceState.UNKNOWN) return OverallStatus.UNKNOWN
            // Hard RED conditions.
            if (fg == ServiceState.STOPPED) return OverallStatus.RED
            if (listener == ServiceState.STOPPED) return OverallStatus.RED
            if (listener == ServiceState.PERMISSION_MISSING) return OverallStatus.RED

            // AMBER: degraded but still alive.
            if (fg == ServiceState.DEGRADED || listener == ServiceState.DEGRADED) return OverallStatus.AMBER
            if (fg == ServiceState.STARTING || listener == ServiceState.STARTING) return OverallStatus.AMBER

            // GREEN: FGS RUNNING + listener RUNNING or user-DISABLED.
            val listenerOk = listener == ServiceState.RUNNING || listener == ServiceState.DISABLED
            if (fg == ServiceState.RUNNING && listenerOk) return OverallStatus.GREEN

            return OverallStatus.UNKNOWN
        }
    }
}
