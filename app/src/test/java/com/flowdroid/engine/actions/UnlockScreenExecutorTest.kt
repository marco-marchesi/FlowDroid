package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.AccessibilityError
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UnlockScreenExecutorTest {

    private fun ctx() = ExecutionContext(
        executionId = "exec1",
        flow = Flow(
            id = "flow-1", name = "F", enabled = true,
            triggers = listOf(Trigger.NotificationPosted()),
            actions = emptyList(), createdAt = 0L, updatedAt = 0L,
        ),
        triggerEvent = TriggerEvent.NotificationFired(
            event = NotificationEvent(
                sbnKey = "k", packageName = "p", postTimeMillis = 0L,
                notificationPostTimeMillis = 0L,
                title = "t", text = null, bigText = null, subText = null, tickerText = null,
                channelId = null, groupKey = null,
                isOngoing = false, isClearable = true, isGroupSummary = false,
                importance = 3, notificationId = 1, actionLabels = emptyList(),
                rawExtrasJson = null,
            ),
            sbnKey = "k",
        ),
        variables = mutableMapOf(),
    )

    @Test fun `successful unlock returns Ok`() = runTest {
        val controller = mockk<AccessibilityController>()
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns Outcome.ok(Unit)
        val executor = UnlockScreenExecutor(controller, TimberStructuredLogger())
        val r = executor.execute(Action.UnlockScreen(timeoutMs = 5000L), ctx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test fun `keyguard dismissal denied maps to SystemFailure`() = runTest {
        val controller = mockk<AccessibilityController>()
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns
            Outcome.err(AccessibilityError.KeyguardDismissDenied)
        val executor = UnlockScreenExecutor(controller, TimberStructuredLogger())
        val r = executor.execute(Action.UnlockScreen(), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.SystemFailure::class.java)
    }

    @Test fun `timeout maps to ExecutionError Timeout`() = runTest {
        val controller = mockk<AccessibilityController>()
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns
            Outcome.err(AccessibilityError.Timeout(5000L))
        val executor = UnlockScreenExecutor(controller, TimberStructuredLogger())
        val r = executor.execute(Action.UnlockScreen(timeoutMs = 5000L), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.Timeout::class.java)
    }

    @Test fun `timeoutMs is clamped before passing to controller`() = runTest {
        val controller = mockk<AccessibilityController>()
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns Outcome.ok(Unit)
        val executor = UnlockScreenExecutor(controller, TimberStructuredLogger())

        // 100 ms is below MIN_TIMEOUT_MS (1000) → clamped up.
        executor.execute(Action.UnlockScreen(timeoutMs = 100L), ctx())
        // 99 999 ms is above MAX_TIMEOUT_MS (15 000) → clamped down.
        executor.execute(Action.UnlockScreen(timeoutMs = 99_999L), ctx())
        io.mockk.coVerify { controller.ensureScreenOnAndUnlocked(1000L) }
        io.mockk.coVerify { controller.ensureScreenOnAndUnlocked(15_000L) }
    }
}
