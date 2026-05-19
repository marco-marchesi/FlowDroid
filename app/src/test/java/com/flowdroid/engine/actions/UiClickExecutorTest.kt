package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.AccessibilityError
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ClickMode
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.TextMatch
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.common.flow.UiTargetMode
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UiClickExecutorTest {

    private val controller = mockk<AccessibilityController>()
    private val magic = MagicTextEngineImpl()
    private val logger = TimberStructuredLogger()
    private val executor = UiClickExecutor(controller, magic, logger)

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

    @Test fun `coordinates mode taps`() = runTest {
        every { controller.isReady() } returns true
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns Outcome.ok(Unit)
        coEvery { controller.tap(any(), any(), any(), any()) } returns Outcome.ok(Unit)
        val r = executor.execute(
            Action.UiClick(targetMode = UiTargetMode.COORDINATES, x = 100f, y = 200f),
            execCtx(),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        coVerify(exactly = 1) { controller.tap(100f, 200f, ClickMode.SINGLE, 600L) }
    }

    @Test fun `service not ready yields PermissionMissing`() = runTest {
        every { controller.isReady() } returns false
        val r = executor.execute(
            Action.UiClick(targetMode = UiTargetMode.COORDINATES, x = 0f, y = 0f),
            execCtx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        val err = (r as Outcome.Err).error
        assertThat(err).isInstanceOf(ExecutionError.PermissionMissing::class.java)
        assertThat((err as ExecutionError.PermissionMissing).permission)
            .isEqualTo("BIND_ACCESSIBILITY_SERVICE")
    }

    @Test fun `BY_TEXT with magic-text resolves needle`() = runTest {
        every { controller.isReady() } returns true
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns Outcome.ok(Unit)
        coEvery {
            controller.clickByNode(any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.ok(Unit)
        val r = executor.execute(
            Action.UiClick(
                targetMode = UiTargetMode.BY_TEXT,
                text = "{notification.title}",
                textMatch = TextMatch.EXACT,
            ),
            execCtx(mutableMapOf("notification.title" to "Acknowledge")),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        coVerify(exactly = 1) {
            controller.clickByNode(
                UiTargetMode.BY_TEXT, "Acknowledge", TextMatch.EXACT,
                null, ClickMode.SINGLE, 600L, 5000L,
            )
        }
    }

    @Test fun `BY_TEXT with blank needle yields InvalidParam`() = runTest {
        every { controller.isReady() } returns true
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns Outcome.ok(Unit)
        val r = executor.execute(
            Action.UiClick(targetMode = UiTargetMode.BY_TEXT, text = "  "),
            execCtx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `controller NodeNotFound maps to TargetNotFound`() = runTest {
        every { controller.isReady() } returns true
        coEvery { controller.ensureScreenOnAndUnlocked(any()) } returns Outcome.ok(Unit)
        coEvery {
            controller.clickByNode(any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.err(AccessibilityError.NodeNotFound(UiTargetMode.BY_TEXT, "Acknowledge"))
        val r = executor.execute(
            Action.UiClick(targetMode = UiTargetMode.BY_TEXT, text = "Acknowledge"),
            execCtx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.TargetNotFound::class.java)
    }

    @Test fun `keyguard denial maps to PermissionMissing`() = runTest {
        every { controller.isReady() } returns true
        coEvery {
            controller.ensureScreenOnAndUnlocked(any())
        } returns Outcome.err(AccessibilityError.KeyguardDismissDenied)
        val r = executor.execute(
            Action.UiClick(targetMode = UiTargetMode.COORDINATES, x = 1f, y = 1f),
            execCtx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        val err = (r as Outcome.Err).error
        assertThat(err).isInstanceOf(ExecutionError.PermissionMissing::class.java)
        assertThat((err as ExecutionError.PermissionMissing).permission).isEqualTo("DISMISS_KEYGUARD")
    }
}
