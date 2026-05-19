package com.flowdroid.ui.log

import app.cash.turbine.test
import com.flowdroid.common.FakeClock
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.repo.LogRepository
import com.flowdroid.common.repo.NotificationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var notifRepo: NotificationRepository
    private lateinit var logRepo: LogRepository
    private val now = 1_700_000_000_000L
    private val clock = FakeClock(wallMillis = now)

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        notifRepo = mockk()
        logRepo = mockk()
    }

    @AfterEach fun tearDown() { Dispatchers.resetMain() }

    private fun notif(id: Long, postedAtOffsetMs: Long): NotificationEvent = NotificationEvent(
        id = id,
        sbnKey = "k$id",
        packageName = "com.app",
        postTimeMillis = now + postedAtOffsetMs,
        notificationPostTimeMillis = now + postedAtOffsetMs,
        title = "n$id",
        text = null,
        bigText = null,
        subText = null,
        tickerText = null,
        channelId = null,
        groupKey = null,
        isOngoing = false,
        isClearable = true,
        isGroupSummary = false,
        importance = null,
        notificationId = id.toInt(),
        actionLabels = emptyList(),
        rawExtrasJson = null,
    )

    private fun log(id: Long, level: LogEntry.Level): LogEntry = LogEntry(
        id = id,
        timestampMillis = now,
        level = level,
        tag = "t",
        message = "m$id",
        fieldsJson = null,
        throwableClass = null,
        throwableMessage = null,
        stackTraceFirstLines = null,
    )

    @Test fun `LastHour filter drops events older than one hour`() = runTest(dispatcher) {
        val flow = MutableStateFlow(
            listOf(
                notif(1, postedAtOffsetMs = -30 * 60_000L),       // 30m ago — in range
                notif(2, postedAtOffsetMs = -2 * 60 * 60_000L),    // 2h ago — out of range
            ),
        )
        every { notifRepo.observeRecent(any()) } returns flow
        every { logRepo.observeRecent(any(), any()) } returns MutableStateFlow(emptyList())

        val vm = LogViewModel(notifRepo, logRepo, clock)

        vm.notifications.test {
            // Initial empty.
            assertThat(awaitItem()).isEmpty()
            // Default = All -> both visible.
            assertThat(awaitItem().map { it.id }).containsExactly(1L, 2L).inOrder()

            vm.setNotificationFilter(NotificationFilter.LastHour)
            assertThat(awaitItem().map { it.id }).containsExactly(1L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setLogFilter switches underlying repo subscription minLevel`() = runTest(dispatcher) {
        val debugFlow = MutableStateFlow(
            listOf(log(1, LogEntry.Level.DEBUG), log(2, LogEntry.Level.WARN)),
        )
        val warnFlow = MutableStateFlow(listOf(log(2, LogEntry.Level.WARN)))
        every { logRepo.observeRecent(any(), LogEntry.Level.DEBUG) } returns debugFlow
        every { logRepo.observeRecent(any(), LogEntry.Level.WARN) } returns warnFlow
        every { logRepo.observeRecent(any(), LogEntry.Level.INFO) } returns debugFlow
        every { logRepo.observeRecent(any(), LogEntry.Level.ERROR) } returns MutableStateFlow(emptyList())
        every { notifRepo.observeRecent(any()) } returns MutableStateFlow(emptyList())

        val vm = LogViewModel(notifRepo, logRepo, clock)

        vm.appLog.test {
            // Initial empty -> debug flow value (default = AllLevels).
            assertThat(awaitItem()).isEmpty()
            val first = awaitItem()
            assertThat(first.map { it.level }).containsExactly(LogEntry.Level.DEBUG, LogEntry.Level.WARN)

            vm.setLogFilter(LogLevelFilter.WarnPlus)
            val second = awaitItem()
            assertThat(second.map { it.level }).containsExactly(LogEntry.Level.WARN)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `notification query filters by package and title (case-insensitive)`() = runTest(dispatcher) {
        val a = notif(1, postedAtOffsetMs = 0).copy(packageName = "com.example.app", title = "Match available")
        val b = notif(2, postedAtOffsetMs = 0).copy(packageName = "com.calendar", title = "Stand-up reminder")
        every { notifRepo.observeRecent(any()) } returns MutableStateFlow(listOf(a, b))
        every { logRepo.observeRecent(any(), any()) } returns MutableStateFlow(emptyList())

        val vm = LogViewModel(notifRepo, logRepo, clock)
        vm.notifications.test {
            assertThat(awaitItem()).isEmpty()  // initial
            assertThat(awaitItem().map { it.id }).containsExactly(1L, 2L)

            vm.setNotificationQuery("ECHO")
            assertThat(awaitItem().map { it.id }).containsExactly(1L)

            vm.setNotificationQuery("stand")  // matches title
            assertThat(awaitItem().map { it.id }).containsExactly(2L)

            vm.setNotificationQuery("")
            assertThat(awaitItem().map { it.id }).containsExactly(1L, 2L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `log query filters by tag and message`() = runTest(dispatcher) {
        val a = log(1, LogEntry.Level.INFO).copy(tag = "Engine", message = "flow match")
        val b = log(2, LogEntry.Level.INFO).copy(tag = "Watchdog", message = "rebind requested")
        every { logRepo.observeRecent(any(), any()) } returns MutableStateFlow(listOf(a, b))
        every { notifRepo.observeRecent(any()) } returns MutableStateFlow(emptyList())

        val vm = LogViewModel(notifRepo, logRepo, clock)
        vm.appLog.test {
            assertThat(awaitItem()).isEmpty()
            assertThat(awaitItem().map { it.id }).containsExactly(1L, 2L)

            vm.setLogQuery("engine")
            assertThat(awaitItem().map { it.id }).containsExactly(1L)

            vm.setLogQuery("rebind")
            assertThat(awaitItem().map { it.id }).containsExactly(2L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `exportNotificationsText emits one tab-separated row per visible event`() = runTest(dispatcher) {
        val a = notif(1, postedAtOffsetMs = 0).copy(packageName = "com.example.app", title = "Match")
        every { notifRepo.observeRecent(any()) } returns MutableStateFlow(listOf(a))
        every { logRepo.observeRecent(any(), any()) } returns MutableStateFlow(emptyList())

        val vm = LogViewModel(notifRepo, logRepo, clock)
        // Force the StateFlow to materialise the latest value (StandardTestDispatcher needs an advance).
        vm.notifications.test {
            awaitItem()  // initial empty
            awaitItem()  // populated
            cancelAndIgnoreRemainingEvents()
        }
        val txt = vm.exportNotificationsText()
        assertThat(txt).contains("com.example.app")
        assertThat(txt).contains("Match")
        // 1 header row + 1 data row.
        assertThat(txt.lines().filter { it.isNotEmpty() }).hasSize(2)
    }

    @Test fun `exportLogText respects current filter and query`() = runTest(dispatcher) {
        val a = log(1, LogEntry.Level.INFO).copy(tag = "Engine", message = "match")
        val b = log(2, LogEntry.Level.INFO).copy(tag = "Watchdog", message = "rebind")
        every { logRepo.observeRecent(any(), any()) } returns MutableStateFlow(listOf(a, b))
        every { notifRepo.observeRecent(any()) } returns MutableStateFlow(emptyList())

        val vm = LogViewModel(notifRepo, logRepo, clock)
        vm.appLog.test {
            awaitItem(); awaitItem()
            vm.setLogQuery("rebind")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        val txt = vm.exportLogText()
        assertThat(txt).contains("rebind")
        assertThat(txt).doesNotContain("Engine")
    }

    @Test fun `repo failure becomes empty list, does not crash`() = runTest(dispatcher) {
        every { notifRepo.observeRecent(any()) } returns kotlinx.coroutines.flow.flow { throw IllegalStateException("boom") }
        every { logRepo.observeRecent(any(), any()) } returns MutableStateFlow(emptyList())

        val vm = LogViewModel(notifRepo, logRepo, clock)

        vm.notifications.test {
            assertThat(awaitItem()).isEmpty()
            // After the throw, .catch emits emptyList — same value, but no crash. Just complete cleanly.
            cancelAndIgnoreRemainingEvents()
        }
    }
}
