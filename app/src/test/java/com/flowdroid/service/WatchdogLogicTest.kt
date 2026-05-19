package com.flowdroid.service

import com.flowdroid.common.FakeClock
import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.HealthSnapshot.OverallStatus
import com.flowdroid.common.ServiceState
import com.flowdroid.common.WatchdogOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WatchdogLogicTest {

    private val clock = FakeClock(wallMillis = 100_000L, elapsedMillis = 10 * 60 * 1000L)

    private fun snapshot(
        fg: ServiceState = ServiceState.RUNNING,
        listener: ServiceState = ServiceState.RUNNING,
        rebindsLast24h: Int = 0,
    ) = HealthSnapshot(
        foregroundServiceState = fg,
        notificationListenerState = listener,
        accessibilityServiceState = ServiceState.UNKNOWN,
        notificationsBoundSinceMillis = null,
        foregroundServiceUptimeMillis = 0L,
        lastListenerDisconnectMillis = null,
        rebindAttemptsLast24h = rebindsLast24h,
        notificationsReceivedLast24h = 0,
        overallStatus = OverallStatus.GREEN,
    )

    @Test fun `green snapshot yields ALL_OK`() {
        val r = WatchdogLogic.decideWatchdogAction(snapshot(), clock, processStartElapsed = 0L)
        assertThat(r).isEqualTo(WatchdogOutcome.ALL_OK)
    }

    @Test fun `listener PERMISSION_MISSING short-circuits to PERMISSION_MISSING`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(listener = ServiceState.PERMISSION_MISSING),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.PERMISSION_MISSING)
    }

    @Test fun `FGS STOPPED triggers restart`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(fg = ServiceState.STOPPED),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED)
    }

    @Test fun `FGS DEGRADED triggers restart`() {
        // Added after F-008: previously DEGRADED slipped through to ALL_OK.
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(fg = ServiceState.DEGRADED),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED)
    }

    @Test fun `FGS UNKNOWN inside boot grace window does not trigger restart`() {
        // process just started — current elapsed = boot grace - 1
        clock.setElapsed(WatchdogLogic.BOOT_GRACE_MILLIS - 1)
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(fg = ServiceState.UNKNOWN, listener = ServiceState.RUNNING),
            clock, processStartElapsed = 0L,
        )
        // No FGS restart yet; listener is fine.
        assertThat(r).isAnyOf(WatchdogOutcome.ALL_OK, WatchdogOutcome.REBIND_REQUESTED)
        assertThat(r).isNotEqualTo(WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED)
    }

    @Test fun `FGS UNKNOWN past boot grace triggers restart`() {
        clock.setElapsed(WatchdogLogic.BOOT_GRACE_MILLIS + 1)
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(fg = ServiceState.UNKNOWN, listener = ServiceState.RUNNING),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED)
    }

    @Test fun `null process start treated as long ago (no grace)`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(fg = ServiceState.UNKNOWN, listener = ServiceState.RUNNING),
            clock, processStartElapsed = null,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED)
    }

    @Test fun `listener DEGRADED with few rebinds requests normal rebind`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(listener = ServiceState.DEGRADED, rebindsLast24h = 1),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.REBIND_REQUESTED)
    }

    @Test fun `listener DEGRADED with many rebinds escalates to toggle`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(
                listener = ServiceState.DEGRADED,
                rebindsLast24h = WatchdogLogic.DEGRADED_TOGGLE_THRESHOLD,
            ),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.TOGGLED_COMPONENT)
    }

    @Test fun `listener STOPPED requests rebind`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(listener = ServiceState.STOPPED),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.REBIND_REQUESTED)
    }

    @Test fun `listener DISABLED is user-OK and yields ALL_OK`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(listener = ServiceState.DISABLED),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.ALL_OK)
    }

    @Test fun `listener UNKNOWN does not trigger rebind`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(listener = ServiceState.UNKNOWN),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.ALL_OK)
    }

    @Test fun `priority order — permission missing wins over FGS down`() {
        val r = WatchdogLogic.decideWatchdogAction(
            snapshot(fg = ServiceState.STOPPED, listener = ServiceState.PERMISSION_MISSING),
            clock, processStartElapsed = 0L,
        )
        assertThat(r).isEqualTo(WatchdogOutcome.PERMISSION_MISSING)
    }
}
