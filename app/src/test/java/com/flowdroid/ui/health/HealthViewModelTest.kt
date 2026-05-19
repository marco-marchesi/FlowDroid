package com.flowdroid.ui.health

import app.cash.turbine.test
import com.flowdroid.common.FakeClock
import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.ServiceState
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.NotificationRepository
import com.flowdroid.common.service.ServiceRegistry
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var registry: ServiceRegistry
    private lateinit var healthRepo: HealthRepository
    private lateinit var notifRepo: NotificationRepository
    private val clock = FakeClock(wallMillis = 1_700_000_000_000L)

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        registry = mockk()
        healthRepo = mockk()
        notifRepo = mockk()
    }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun greenSnapshot() = HealthSnapshot(
        foregroundServiceState = ServiceState.RUNNING,
        notificationListenerState = ServiceState.RUNNING,
        accessibilityServiceState = ServiceState.PERMISSION_MISSING,
        notificationsBoundSinceMillis = 1_700_000_000_000L - 60_000L,
        foregroundServiceUptimeMillis = 60_000L,
        lastListenerDisconnectMillis = null,
        rebindAttemptsLast24h = 0,
        notificationsReceivedLast24h = 12,
        overallStatus = HealthSnapshot.OverallStatus.GREEN,
    )

    @Test fun `Ready state combines snapshot and disconnect journal`() = runTest(dispatcher) {
        val snapshots = MutableStateFlow(greenSnapshot())
        every { registry.observeSnapshot() } returns snapshots
        val disconnect = HealthEvent(
            id = 1,
            timestampMillis = 1_700_000_000_000L - 10_000L,
            kind = HealthEvent.Kind.LISTENER_DISCONNECTED,
            message = "Listener disconnected",
            outcome = "REBIND_REQUESTED",
        )
        val healthEvents = MutableStateFlow(listOf(disconnect))
        every { healthRepo.observeRecent(any()) } returns healthEvents

        val vm = HealthViewModel(registry, healthRepo, notifRepo, clock)

        vm.state.test {
            // Loading initial.
            assertThat(awaitItem()).isEqualTo(HealthUiState.Loading)
            val ready = awaitItem()
            assertThat(ready).isInstanceOf(HealthUiState.Ready::class.java)
            val r = ready as HealthUiState.Ready
            assertThat(r.snapshot.overallStatus).isEqualTo(HealthSnapshot.OverallStatus.GREEN)
            assertThat(r.disconnects).hasSize(1)
            assertThat(r.listenerDisconnectsLast24h).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `Ready state updates when snapshot transitions to RED`() = runTest(dispatcher) {
        val snapshots = MutableStateFlow(greenSnapshot())
        every { registry.observeSnapshot() } returns snapshots
        every { healthRepo.observeRecent(any()) } returns MutableStateFlow(emptyList())

        val vm = HealthViewModel(registry, healthRepo, notifRepo, clock)

        vm.state.test {
            // Skip Loading.
            awaitItem()
            val first = awaitItem() as HealthUiState.Ready
            assertThat(first.snapshot.overallStatus).isEqualTo(HealthSnapshot.OverallStatus.GREEN)

            snapshots.value = greenSnapshot().copy(
                overallStatus = HealthSnapshot.OverallStatus.RED,
                notificationListenerState = ServiceState.STOPPED,
                lastListenerDisconnectMillis = 1_700_000_000_000L - 240_000L,
            )

            val second = awaitItem() as HealthUiState.Ready
            assertThat(second.snapshot.overallStatus).isEqualTo(HealthSnapshot.OverallStatus.RED)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failing source flow becomes Error state — does not crash`() = runTest(dispatcher) {
        every { registry.observeSnapshot() } returns flow { throw IllegalStateException("registry boom") }
        every { healthRepo.observeRecent(any()) } returns MutableStateFlow(emptyList())

        val vm = HealthViewModel(registry, healthRepo, notifRepo, clock)

        vm.state.test {
            assertThat(awaitItem()).isEqualTo(HealthUiState.Loading)
            val err = awaitItem()
            assertThat(err).isInstanceOf(HealthUiState.Error::class.java)
            assertThat((err as HealthUiState.Error).message).contains("registry boom")
            cancelAndIgnoreRemainingEvents()
        }
    }

}
