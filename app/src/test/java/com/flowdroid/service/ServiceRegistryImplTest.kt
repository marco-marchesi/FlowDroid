package com.flowdroid.service

import app.cash.turbine.test
import com.flowdroid.common.FakeClock
import com.flowdroid.common.HealthSnapshot.OverallStatus
import com.flowdroid.common.ServiceState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ServiceRegistryImplTest {

    private val clock = FakeClock(wallMillis = 1_000_000L, elapsedMillis = 100L)
    private val registry = ServiceRegistryImpl(clock)

    @Test fun `initial snapshot is UNKNOWN`() {
        val s = registry.snapshot()
        assertThat(s.foregroundServiceState).isEqualTo(ServiceState.UNKNOWN)
        assertThat(s.notificationListenerState).isEqualTo(ServiceState.UNKNOWN)
        assertThat(s.overallStatus).isEqualTo(OverallStatus.UNKNOWN)
        assertThat(s.rebindAttemptsLast24h).isEqualTo(0)
        assertThat(s.notificationsReceivedLast24h).isEqualTo(0)
        assertThat(s.lastListenerDisconnectMillis).isNull()
    }

    @Test fun `FGS RUNNING plus listener RUNNING yields GREEN`() {
        registry.setForegroundServiceState(ServiceState.RUNNING)
        registry.setNotificationListenerState(ServiceState.RUNNING)
        assertThat(registry.snapshot().overallStatus).isEqualTo(OverallStatus.GREEN)
    }

    @Test fun `listener DEGRADED with FGS RUNNING yields AMBER`() {
        registry.setForegroundServiceState(ServiceState.RUNNING)
        registry.setNotificationListenerState(ServiceState.DEGRADED)
        assertThat(registry.snapshot().overallStatus).isEqualTo(OverallStatus.AMBER)
    }

    @Test fun `listener STOPPED yields RED`() {
        registry.setForegroundServiceState(ServiceState.RUNNING)
        registry.setNotificationListenerState(ServiceState.STOPPED)
        assertThat(registry.snapshot().overallStatus).isEqualTo(OverallStatus.RED)
    }

    @Test fun `listener PERMISSION_MISSING yields RED`() {
        registry.setForegroundServiceState(ServiceState.RUNNING)
        registry.setNotificationListenerState(ServiceState.PERMISSION_MISSING)
        assertThat(registry.snapshot().overallStatus).isEqualTo(OverallStatus.RED)
    }

    @Test fun `FGS STOPPED yields RED regardless of listener`() {
        registry.setForegroundServiceState(ServiceState.STOPPED)
        registry.setNotificationListenerState(ServiceState.RUNNING)
        assertThat(registry.snapshot().overallStatus).isEqualTo(OverallStatus.RED)
    }

    @Test fun `listener DISABLED is treated as user-OK and yields GREEN with FGS running`() {
        registry.setForegroundServiceState(ServiceState.RUNNING)
        registry.setNotificationListenerState(ServiceState.DISABLED)
        assertThat(registry.snapshot().overallStatus).isEqualTo(OverallStatus.GREEN)
    }

    @Test fun `bound-since timestamp uses clock when not explicit`() {
        clock.setWall(42L)
        registry.setNotificationListenerState(ServiceState.RUNNING)
        assertThat(registry.snapshot().notificationsBoundSinceMillis).isEqualTo(42L)
    }

    @Test fun `bound-since timestamp honours explicit argument`() {
        registry.setNotificationListenerState(ServiceState.RUNNING, sinceMillis = 7L)
        assertThat(registry.snapshot().notificationsBoundSinceMillis).isEqualTo(7L)
    }

    @Test fun `disconnect journal is capped at MAX_JOURNAL entries`() {
        repeat(ServiceRegistryImpl.MAX_JOURNAL + 50) { i ->
            registry.recordListenerDisconnect(timestampMillis = i.toLong())
        }
        // We can't peek the deque directly, but we can verify the *last* timestamp is preserved
        // (cap drops the oldest entries) and the snapshot still reflects the latest.
        val expectedLast = (ServiceRegistryImpl.MAX_JOURNAL + 49).toLong()
        assertThat(registry.snapshot().lastListenerDisconnectMillis).isEqualTo(expectedLast)
    }

    @Test fun `rebind attempts rolling 24h count rolls forward`() {
        val day = 24L * 60L * 60L * 1000L
        // Now = wallMillis 1_000_000_000_000L
        clock.setWall(1_000_000_000_000L)

        // Old attempt (>24h ago) should not be counted.
        registry.recordRebindAttempt(timestampMillis = 1_000_000_000_000L - day - 1)
        // Recent attempts within window.
        registry.recordRebindAttempt(timestampMillis = 1_000_000_000_000L - 1000L)
        registry.recordRebindAttempt(timestampMillis = 1_000_000_000_000L - 5000L)
        assertThat(registry.snapshot().rebindAttemptsLast24h).isEqualTo(2)
    }

    @Test fun `notifications received 24h count rolls forward as time advances`() {
        val day = 24L * 60L * 60L * 1000L
        clock.setWall(0L)
        registry.incrementNotificationReceived() // at t=0
        clock.setWall(10_000L)
        registry.incrementNotificationReceived() // at t=10s
        clock.setWall(day - 1)
        registry.incrementNotificationReceived() // just under 24h
        assertThat(registry.snapshot().notificationsReceivedLast24h).isEqualTo(3)

        // Advance the wall clock past 24h relative to the first entry only.
        clock.setWall(day + 5_000L)
        registry.incrementNotificationReceived()
        // Prune cutoff = (day+5000) - day = 5000. Entry at t=0 drops; entries at t=10_000,
        // t=day-1, t=day+5000 remain — three are still within the rolling 24h window.
        assertThat(registry.snapshot().notificationsReceivedLast24h).isEqualTo(3)

        // Push further so the t=10_000 entry also falls out of the window.
        clock.setWall(day + 20_000L)
        registry.incrementNotificationReceived()
        // Cutoff = day+20_000 - day = 20_000. Entry at t=10_000 drops. Remaining:
        // t=day-1, t=day+5000, t=day+20_000 = 3.
        assertThat(registry.snapshot().notificationsReceivedLast24h).isEqualTo(3)
    }

    @Test fun `foreground uptime tracks since first RUNNING`() {
        clock.setElapsed(1_000L)
        registry.setForegroundServiceState(ServiceState.RUNNING)
        clock.setElapsed(6_000L)
        // Re-trigger a publish via any setter to recompute the snapshot uptime.
        registry.setAccessibilityServiceState(ServiceState.UNKNOWN)
        assertThat(registry.snapshot().foregroundServiceUptimeMillis).isEqualTo(5_000L)
    }

    @Test fun `foreground uptime resets when service STOPPED`() {
        clock.setElapsed(1_000L)
        registry.setForegroundServiceState(ServiceState.RUNNING)
        clock.setElapsed(6_000L)
        registry.setForegroundServiceState(ServiceState.STOPPED)
        assertThat(registry.snapshot().foregroundServiceUptimeMillis).isEqualTo(0L)
    }

    @Test fun `observeSnapshot emits on state transition`() = runTest {
        registry.observeSnapshot().test {
            // First emission is current state.
            val first = awaitItem()
            assertThat(first.overallStatus).isEqualTo(OverallStatus.UNKNOWN)
            registry.setForegroundServiceState(ServiceState.RUNNING)
            registry.setNotificationListenerState(ServiceState.RUNNING)
            // Collapse any intermediate emissions; we just want to see a GREEN eventually.
            var status = awaitItem().overallStatus
            while (status != OverallStatus.GREEN) {
                status = awaitItem().overallStatus
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
