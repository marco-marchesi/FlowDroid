package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.LoopMode
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LoopExecutorTest {

    /** Records the variables map snapshot at each invocation so the test can inspect iteration vars. */
    private class CapturingRunner(
        private val abortAt: Int = -1,
    ) : ActionRunner {
        val itemValues = mutableListOf<String>()
        val indexValues = mutableListOf<String>()
        var calls = 0
        override suspend fun runActions(
            actions: List<Action>,
            context: ExecutionContext,
        ): ActionRunner.RunStats {
            itemValues += context.variables["item"] ?: ""
            indexValues += context.variables["index"] ?: ""
            val aborted = (calls == abortAt)
            calls++
            return ActionRunner.RunStats(
                okCount = if (aborted) 0 else actions.size,
                errCount = if (aborted) 1 else 0,
                aborted = aborted,
            )
        }
    }

    private fun executor(runner: ActionRunner) = LoopExecutor(
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

    @Test fun `count mode runs body N times and exposes 0-based index`() = runTest {
        val runner = CapturingRunner()
        val action = Action.Loop(
            mode = LoopMode.COUNT,
            count = 3,
            actions = listOf(Action.Delay(millis = 1L)),
        )
        val r = executor(runner).execute(action, ctx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(runner.calls).isEqualTo(3)
        assertThat(runner.indexValues).containsExactly("0", "1", "2").inOrder()
    }

    @Test fun `count zero is a valid no-op`() = runTest {
        val runner = CapturingRunner()
        val r = executor(runner).execute(
            Action.Loop(mode = LoopMode.COUNT, count = 0, actions = emptyList()),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(runner.calls).isEqualTo(0)
    }

    @Test fun `negative count is rejected with InvalidParam`() = runTest {
        val runner = CapturingRunner()
        val r = executor(runner).execute(
            Action.Loop(mode = LoopMode.COUNT, count = -1, actions = emptyList()),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `count clamped to MAX_ITERATIONS`() = runTest {
        val runner = CapturingRunner()
        executor(runner).execute(
            Action.Loop(mode = LoopMode.COUNT, count = Action.Loop.MAX_ITERATIONS + 50, actions = emptyList()),
            ctx(),
        )
        assertThat(runner.calls).isEqualTo(Action.Loop.MAX_ITERATIONS)
    }

    @Test fun `foreach lines mode iterates non-empty trimmed lines`() = runTest {
        val runner = CapturingRunner()
        val vars = mutableMapOf("body" to "alpha\n  beta  \n\ngamma\n")
        executor(runner).execute(
            Action.Loop(
                mode = LoopMode.FOREACH_LINES,
                list = "{body}",
                actions = listOf(Action.Delay(millis = 1L)),
            ),
            ctx(vars),
        )
        assertThat(runner.itemValues).containsExactly("alpha", "beta", "gamma").inOrder()
    }

    @Test fun `custom item and index vars are honored`() = runTest {
        val runner = object : ActionRunner {
            val items = mutableListOf<String>()
            val indices = mutableListOf<String>()
            override suspend fun runActions(actions: List<Action>, context: ExecutionContext): ActionRunner.RunStats {
                items += context.variables["x"] ?: "?"
                indices += context.variables["i"] ?: "?"
                return ActionRunner.RunStats(0, 0, false)
            }
        }
        executor(runner).execute(
            Action.Loop(
                mode = LoopMode.FOREACH_LINES,
                list = "a\nb",
                itemVar = "x",
                indexVar = "i",
                actions = listOf(Action.Delay(1L)),
            ),
            ctx(),
        )
        assertThat(runner.items).containsExactly("a", "b").inOrder()
        assertThat(runner.indices).containsExactly("0", "1").inOrder()
    }

    @Test fun `iteration abort short-circuits loop when continueOnError is false`() = runTest {
        val runner = CapturingRunner(abortAt = 1)
        executor(runner).execute(
            Action.Loop(
                mode = LoopMode.COUNT,
                count = 5,
                actions = listOf(Action.Delay(1L)),
                continueOnError = false,
            ),
            ctx(),
        )
        // 0-th iteration runs ok, 1st aborts → no more iterations.
        assertThat(runner.calls).isEqualTo(2)
    }

    @Test fun `iteration abort keeps going when continueOnError is true`() = runTest {
        val runner = CapturingRunner(abortAt = 1)
        executor(runner).execute(
            Action.Loop(
                mode = LoopMode.COUNT,
                count = 4,
                actions = listOf(Action.Delay(1L)),
                continueOnError = true,
            ),
            ctx(),
        )
        assertThat(runner.calls).isEqualTo(4)
    }
}
