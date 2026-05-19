package com.flowdroid.common.service

import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.ServiceState
import kotlinx.coroutines.flow.Flow

/**
 * In-memory shared state for service health.
 *
 * Services post their state here on every transition; UI observes for live updates;
 * the watchdog reads it to decide whether intervention is needed.
 *
 * Implementations MUST be thread-safe — services post from arbitrary threads.
 *
 * This contract is the bridge between platform services (which can't easily expose Flow APIs
 * directly to other Android services) and the rest of the app.
 */
interface ServiceRegistry {

    /** Live overall snapshot. UI binds to this. */
    fun observeSnapshot(): Flow<HealthSnapshot>

    /** Current snapshot synchronously (for watchdog logic). */
    fun snapshot(): HealthSnapshot

    fun setForegroundServiceState(state: ServiceState)
    fun setNotificationListenerState(state: ServiceState, sinceMillis: Long? = null)
    fun setAccessibilityServiceState(state: ServiceState)

    /** Recorded when listener disconnects so the Health screen can render the journal. */
    fun recordListenerDisconnect(timestampMillis: Long)

    /** Incremented every time the watchdog forces a rebind. */
    fun recordRebindAttempt(timestampMillis: Long)

    /** Cumulative count of notifications received since boot, for the Health screen "today" stat. */
    fun incrementNotificationReceived()
}
