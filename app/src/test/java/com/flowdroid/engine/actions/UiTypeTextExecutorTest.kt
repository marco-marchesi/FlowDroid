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
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UiTypeTextExecutorTest {

    private val controller = mockk<AccessibilityController>()
    private val magic = MagicTextEngineImpl()
    private val logger = TimberStructuredLogger()
    private val executor = UiTypeTextExecutor(controller, magic, logger)

    private fun execCtx(vars: MutableMap<String, String> = mutableMapOf()) = ExecutionContext(
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
        variables = vars,
    )

    @Test fun `forwards expanded text and pressEnter flag`() = runTest {
        every { controller.isReady() } returns true
        coEvery { controller.typeText(any(), any()) } returns Outcome.ok(Unit)
        val r = executor.execute(
            Action.UiTypeText(text = "hello {notification.title}", pressEnterAfter = true),
            execCtx(mutableMapOf("notification.title" to "world")),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        coVerify(exactly = 1) { controller.typeText("hello world", true) }
    }

    @Test fun `service not ready yields PermissionMissing`() = runTest {
        every { controller.isReady() } returns false
        val r = executor.execute(Action.UiTypeText(text = "x"), execCtx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.PermissionMissing::class.java)
    }

    @Test fun `no focused editable maps to TargetNotFound`() = runTest {
        every { controller.isReady() } returns true
        coEvery {
            controller.typeText(any(), any())
        } returns Outcome.err(AccessibilityError.NoFocusedEditableNode)
        val r = executor.execute(Action.UiTypeText(text = "x"), execCtx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        val err = (r as Outcome.Err).error
        assertThat(err).isInstanceOf(ExecutionError.TargetNotFound::class.java)
        assertThat((err as ExecutionError.TargetNotFound).what).contains("focused-editable-node")
    }
}
