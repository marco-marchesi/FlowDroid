package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.CompareOp
import com.flowdroid.common.flow.Condition
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class IfExecutorTest {

    /** Records what runActions was called with so the test can assert which branch ran. */
    private class RecordingRunner : ActionRunner {
        val recorded = mutableListOf<List<Action>>()
        override suspend fun runActions(
            actions: List<Action>,
            context: ExecutionContext,
        ): ActionRunner.RunStats {
            recorded += actions
            return ActionRunner.RunStats(okCount = actions.size, errCount = 0, aborted = false)
        }
    }

    private fun executor(runner: ActionRunner) = IfExecutor(
        runnerLazy = dagger.Lazy { runner },
        magicText = MagicTextEngineImpl(),
        logger = TimberStructuredLogger(),
    )

    private fun ctx(vars: MutableMap<String, String> = mutableMapOf()) = ExecutionContext(
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
                title = "Hello", text = null, bigText = null, subText = null, tickerText = null,
                channelId = null, groupKey = null,
                isOngoing = false, isClearable = true, isGroupSummary = false,
                importance = 3, notificationId = 1, actionLabels = emptyList(),
                rawExtrasJson = null,
            ),
            sbnKey = "k",
        ),
        variables = vars,
    )

    @Test fun `true condition runs the then branch`() = runTest {
        val runner = RecordingRunner()
        val action = Action.If(
            condition = Condition("hi", CompareOp.EQUALS, "hi"),
            thenActions = listOf(Action.Delay(millis = 1L)),
            elseActions = listOf(Action.Delay(millis = 2L)),
        )
        val r = executor(runner).execute(action, ctx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(runner.recorded).hasSize(1)
        assertThat(runner.recorded[0]).hasSize(1)
        assertThat((runner.recorded[0][0] as Action.Delay).millis).isEqualTo(1L)
    }

    @Test fun `false condition runs the else branch`() = runTest {
        val runner = RecordingRunner()
        val action = Action.If(
            condition = Condition("a", CompareOp.EQUALS, "b"),
            thenActions = listOf(Action.Delay(millis = 1L)),
            elseActions = listOf(Action.Delay(millis = 2L)),
        )
        executor(runner).execute(action, ctx())
        assertThat(runner.recorded).hasSize(1)
        assertThat((runner.recorded[0][0] as Action.Delay).millis).isEqualTo(2L)
    }

    @Test fun `empty else branch on false condition runs nothing`() = runTest {
        val runner = RecordingRunner()
        val action = Action.If(
            condition = Condition("a", CompareOp.EQUALS, "b"),
            thenActions = listOf(Action.Delay(millis = 1L)),
        )
        val r = executor(runner).execute(action, ctx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(runner.recorded).hasSize(1)
        assertThat(runner.recorded[0]).isEmpty()
    }

    @Test fun `condition expanded against runtime variables`() = runTest {
        val runner = RecordingRunner()
        val vars = mutableMapOf("status" to "200")
        val action = Action.If(
            condition = Condition("{status}", CompareOp.EQUALS, "200"),
            thenActions = listOf(Action.Delay(millis = 9L)),
            elseActions = listOf(Action.Delay(millis = 0L)),
        )
        executor(runner).execute(action, ctx(vars))
        assertThat((runner.recorded[0][0] as Action.Delay).millis).isEqualTo(9L)
    }
}
