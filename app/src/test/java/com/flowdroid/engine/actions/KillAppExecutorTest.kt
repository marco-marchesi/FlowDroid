package com.flowdroid.engine.actions

import android.app.ActivityManager
import android.content.Context
import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class KillAppExecutorTest {

    private val logger = TimberStructuredLogger()
    private val magic = MagicTextEngineImpl()
    private val context = mockk<Context>(relaxed = true)
    private val am = mockk<ActivityManager>(relaxed = true)

    init {
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns am
    }

    private val executor = KillAppExecutor(context, magic, logger)

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

    @Test fun `kills background processes of resolved package`() = runTest {
        val r = executor.execute(Action.KillApp(packageName = "com.example.app"), execCtx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        verify(exactly = 1) { am.killBackgroundProcesses("com.example.app") }
    }

    @Test fun `blank package yields InvalidParam`() = runTest {
        val r = executor.execute(Action.KillApp(packageName = "   "), execCtx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `SecurityException maps to PermissionMissing`() = runTest {
        every { am.killBackgroundProcesses(any()) } throws SecurityException("denied")
        val r = executor.execute(Action.KillApp(packageName = "com.x"), execCtx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        val err = (r as Outcome.Err).error
        assertThat(err).isInstanceOf(ExecutionError.PermissionMissing::class.java)
        assertThat((err as ExecutionError.PermissionMissing).permission)
            .isEqualTo("android.permission.KILL_BACKGROUND_PROCESSES")
    }

    @Test fun `magic text expansion is applied to packageName`() = runTest {
        val r = executor.execute(
            Action.KillApp(packageName = "{notification.package}"),
            execCtx(mutableMapOf("notification.package" to "com.resolved")),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        verify(exactly = 1) { am.killBackgroundProcesses("com.resolved") }
    }
}
