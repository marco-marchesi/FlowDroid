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
import com.flowdroid.common.flow.UiKey
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UiPressKeyExecutorTest {

    private val controller = mockk<AccessibilityController>()
    private val logger = TimberStructuredLogger()
    private val executor = UiPressKeyExecutor(controller, logger)

    private fun execCtx() = ExecutionContext(
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
                title = null, text = null, bigText = null, subText = null, tickerText = null,
                channelId = null, groupKey = null,
                isOngoing = false, isClearable = true, isGroupSummary = false,
                importance = 3, notificationId = 1, actionLabels = emptyList(),
                rawExtrasJson = null,
            ),
            sbnKey = "k",
        ),
        variables = mutableMapOf(),
    )

    @Test fun `forwards key to controller`() = runTest {
        every { controller.isReady() } returns true
        coEvery { controller.pressKey(any()) } returns Outcome.ok(Unit)
        val r = executor.execute(Action.UiPressKey(key = UiKey.BACK), execCtx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        coVerify(exactly = 1) { controller.pressKey(UiKey.BACK) }
    }

    @Test fun `service not ready yields PermissionMissing`() = runTest {
        every { controller.isReady() } returns false
        val r = executor.execute(Action.UiPressKey(key = UiKey.HOME), execCtx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.PermissionMissing::class.java)
    }

    @Test fun `controller failure maps to SystemFailure`() = runTest {
        every { controller.isReady() } returns true
        coEvery { controller.pressKey(any()) } returns
            Outcome.err(AccessibilityError.GestureFailed("global action refused"))
        val r = executor.execute(Action.UiPressKey(key = UiKey.RECENTS), execCtx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.SystemFailure::class.java)
    }
}
