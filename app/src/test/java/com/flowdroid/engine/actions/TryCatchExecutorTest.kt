package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TryCatchExecutorTest {

    /**
     * Per-invocation outcome programmed by the test. Records which branches actually ran (by tag)
     * so assertions can check try → catch → finally ordering.
     */
    private class ScriptedRunner(
        private val tryFails: Boolean,
    ) : ActionRunner {
        val invocations = mutableListOf<List<Action>>()
        var call = 0
        override suspend fun runActions(
            actions: List<Action>,
            context: ExecutionContext,
        ): ActionRunner.RunStats {
            invocations += actions
            val isTryCall = (call == 0)
            call++
            return if (isTryCall && tryFails) {
                ActionRunner.RunStats(okCount = 0, errCount = 1, aborted = true)
            } else {
                ActionRunner.RunStats(okCount = actions.size, errCount = 0, aborted = false)
            }
        }
    }

    private fun executor(runner: ActionRunner) = TryCatchExecutor(
        runnerLazy = dagger.Lazy { runner },
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
                title = "t", text = null, bigText = null, subText = null, tickerText = null,
                channelId = null, groupKey = null,
                isOngoing = false, isClearable = true, isGroupSummary = false,
                importance = 3, notificationId = 1, actionLabels = emptyList(),
                rawExtrasJson = null,
            ),
            sbnKey = "k",
        ),
        variables = vars,
    )

    @Test fun `successful try skips catch but runs finally`() = runTest {
        val runner = ScriptedRunner(tryFails = false)
        val action = Action.TryCatch(
            tryActions = listOf(Action.Delay(millis = 1L)),
            catchActions = listOf(Action.Delay(millis = 2L)),
            finallyActions = listOf(Action.Delay(millis = 3L)),
        )
        val r = executor(runner).execute(action, ctx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(runner.invocations).hasSize(2)
        assertThat((runner.invocations[0][0] as Action.Delay).millis).isEqualTo(1L)
        assertThat((runner.invocations[1][0] as Action.Delay).millis).isEqualTo(3L)
    }

    @Test fun `failing try triggers catch and then finally`() = runTest {
        val runner = ScriptedRunner(tryFails = true)
        val action = Action.TryCatch(
            tryActions = listOf(Action.Delay(millis = 1L)),
            catchActions = listOf(Action.Delay(millis = 2L)),
            finallyActions = listOf(Action.Delay(millis = 3L)),
        )
        executor(runner).execute(action, ctx())
        assertThat(runner.invocations).hasSize(3)
        assertThat((runner.invocations[0][0] as Action.Delay).millis).isEqualTo(1L)
        assertThat((runner.invocations[1][0] as Action.Delay).millis).isEqualTo(2L)
        assertThat((runner.invocations[2][0] as Action.Delay).millis).isEqualTo(3L)
    }

    @Test fun `no finally branch is fine`() = runTest {
        val runner = ScriptedRunner(tryFails = true)
        val action = Action.TryCatch(
            tryActions = listOf(Action.Delay(millis = 1L)),
            catchActions = listOf(Action.Delay(millis = 2L)),
        )
        executor(runner).execute(action, ctx())
        assertThat(runner.invocations).hasSize(2)
    }

    @Test fun `intoErrorVar captures reason when try aborts`() = runTest {
        val vars = mutableMapOf<String, String>()
        val runner = ScriptedRunner(tryFails = true)
        val action = Action.TryCatch(
            tryActions = listOf(Action.Delay(millis = 1L)),
            catchActions = listOf(Action.Delay(millis = 2L)),
            intoErrorVar = "err",
        )
        executor(runner).execute(action, ctx(vars))
        assertThat(vars["err"]).isEqualTo("action_aborted")
    }

    @Test fun `blank intoErrorVar writes nothing`() = runTest {
        val vars = mutableMapOf<String, String>()
        val runner = ScriptedRunner(tryFails = true)
        val action = Action.TryCatch(
            tryActions = listOf(Action.Delay(millis = 1L)),
            catchActions = listOf(Action.Delay(millis = 2L)),
            intoErrorVar = "",
        )
        executor(runner).execute(action, ctx(vars))
        assertThat(vars).isEmpty()
    }

    @Test fun `success path does not write intoErrorVar`() = runTest {
        val vars = mutableMapOf<String, String>()
        val runner = ScriptedRunner(tryFails = false)
        val action = Action.TryCatch(
            tryActions = listOf(Action.Delay(millis = 1L)),
            catchActions = listOf(Action.Delay(millis = 2L)),
            intoErrorVar = "err",
        )
        executor(runner).execute(action, ctx(vars))
        assertThat(vars).isEmpty()
    }
}
