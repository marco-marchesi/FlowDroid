package com.flowdroid.ui.setup

import app.cash.turbine.test
import com.flowdroid.common.FakeClock
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.permission.OemBatteryHelper
import com.flowdroid.common.permission.OemBrand
import com.flowdroid.common.permission.OemSpecific
import com.flowdroid.common.permission.PermissionChecker
import com.flowdroid.common.permission.PermissionSnapshot
import com.flowdroid.common.repo.NotificationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var permissionChecker: PermissionChecker
    private lateinit var oemHelper: OemBatteryHelper
    private lateinit var notificationRepo: NotificationRepository
    private lateinit var notifier: SelfTestNotifier
    private val now = 1_700_000_000_000L
    private val clock = FakeClock(wallMillis = now)

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        permissionChecker = mockk(relaxed = true)
        oemHelper = mockk()
        notificationRepo = mockk()
        notifier = mockk()
        every { oemHelper.detect() } returns OemBrand.SAMSUNG
        every { permissionChecker.observe() } returns MutableStateFlow(
            PermissionSnapshot(
                notificationListenerEnabled = true,
                accessibilityServiceEnabled = false,
                postNotificationsGranted = true,
                batteryOptimisationIgnored = true,
                exactAlarmAllowed = false,
                overlayPermissionGranted = false,
                oemSpecific = OemSpecific.Samsung(true, true),
            ),
        )
    }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun fakeNotif(title: String, postedAt: Long) = NotificationEvent(
        id = 1, sbnKey = "k", packageName = "com.flowdroid",
        postTimeMillis = postedAt, notificationPostTimeMillis = postedAt,
        title = title, text = null, bigText = null, subText = null,
        tickerText = null, channelId = null, groupKey = null,
        isOngoing = false, isClearable = true, isGroupSummary = false,
        importance = 3, notificationId = 1, actionLabels = emptyList(),
        rawExtrasJson = null,
    )

    @Test fun `runSelfTest happy path emits Success when listener echoes title`() = runTest(dispatcher) {
        every { notifier.post(any()) } returns SelfTestPostOutcome.Posted
        val flow = MutableStateFlow(
            listOf(fakeNotif(SelfTestNotifier.SELF_TEST_TITLE, now)),
        )
        every { notificationRepo.observeRecent(any()) } returns flow

        val vm = SetupViewModel(permissionChecker, oemHelper, notificationRepo, clock, notifier, dispatcher)

        val results = vm.runSelfTest().toList()
        assertThat(results.first()).isEqualTo(SelfTestResult.InProgress)
        assertThat(results.last()).isEqualTo(SelfTestResult.Success)
    }

    @Test fun `runSelfTest rejects stale matching notification from before test start`() = runTest(dispatcher) {
        // Regression for F-009: prior to the fix, any notification with the self-test title in the
        // repo would falsely pass the filter, hiding listener failures from the user.
        every { notifier.post(any()) } returns SelfTestPostOutcome.Posted
        // Repo emits a notif with the right title, but posted 10 seconds BEFORE the test starts.
        val stale = fakeNotif(SelfTestNotifier.SELF_TEST_TITLE, now - 10_000L)
        every { notificationRepo.observeRecent(any()) } returns MutableStateFlow(listOf(stale))

        val vm = SetupViewModel(permissionChecker, oemHelper, notificationRepo, clock, notifier, dispatcher)

        val results = vm.runSelfTest().toList()
        assertThat(results.last()).isEqualTo(SelfTestResult.TimeoutOrNotReceived)
    }

    @Test fun `runSelfTest emits TimeoutOrNotReceived when listener never echoes`() = runTest(dispatcher) {
        every { notifier.post(any()) } returns SelfTestPostOutcome.Posted
        // Repo emits no matching events for the duration of the test.
        every { notificationRepo.observeRecent(any()) } returns MutableStateFlow(emptyList())

        val vm = SetupViewModel(permissionChecker, oemHelper, notificationRepo, clock, notifier, dispatcher)

        val results = vm.runSelfTest().toList()
        assertThat(results.first()).isEqualTo(SelfTestResult.InProgress)
        assertThat(results.last()).isEqualTo(SelfTestResult.TimeoutOrNotReceived)
    }

    @Test fun `runSelfTest emits PostFailed when notifier fails`() = runTest(dispatcher) {
        every { notifier.post(any()) } returns SelfTestPostOutcome.Failed("perm denied")
        every { notificationRepo.observeRecent(any()) } returns MutableStateFlow(emptyList())

        val vm = SetupViewModel(permissionChecker, oemHelper, notificationRepo, clock, notifier, dispatcher)

        val results = vm.runSelfTest().toList()
        assertThat(results).hasSize(2)
        assertThat(results[0]).isEqualTo(SelfTestResult.InProgress)
        assertThat(results[1]).isInstanceOf(SelfTestResult.PostFailed::class.java)
        assertThat((results[1] as SelfTestResult.PostFailed).reason).contains("perm denied")
    }

    @Test fun `state stream exposes Ready with permission snapshot`() = runTest(dispatcher) {
        val vm = SetupViewModel(permissionChecker, oemHelper, notificationRepo, clock, notifier, dispatcher)

        vm.state.test {
            // Initial = Loading.
            assertThat(awaitItem()).isEqualTo(SetupUiState.Loading)
            val ready = awaitItem()
            assertThat(ready).isInstanceOf(SetupUiState.Ready::class.java)
            val r = ready as SetupUiState.Ready
            assertThat(r.brand).isEqualTo(OemBrand.SAMSUNG)
            assertThat(r.snapshot.notificationListenerEnabled).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refresh calls through to PermissionChecker`() = runTest(dispatcher) {
        val vm = SetupViewModel(permissionChecker, oemHelper, notificationRepo, clock, notifier, dispatcher)
        vm.refresh()
        advanceTimeBy(10)
        io.mockk.verify { permissionChecker.refresh() }
    }

}
