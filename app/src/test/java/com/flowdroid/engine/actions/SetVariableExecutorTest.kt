package com.flowdroid.engine.actions

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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SetVariableExecutorTest {

    private val executor = SetVariableExecutor(MagicTextEngineImpl(), TimberStructuredLogger())

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

    @Test fun `literal value is stored under the given name`() = runTest {
        val vars = mutableMapOf<String, String>()
        val r = executor.execute(Action.SetVariable(name = "x", value = "hello"), ctx(vars))
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["x"]).isEqualTo("hello")
    }

    @Test fun `magic text in value is expanded against existing variables`() = runTest {
        val vars = mutableMapOf("greeting" to "ciao")
        val r = executor.execute(
            Action.SetVariable(name = "msg", value = "{greeting} world"),
            ctx(vars),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["msg"]).isEqualTo("ciao world")
    }

    @Test fun `blank name returns InvalidParam`() = runTest {
        val r = executor.execute(Action.SetVariable(name = "  ", value = "anything"), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `overwriting an existing variable replaces its value`() = runTest {
        val vars = mutableMapOf("x" to "old")
        val r = executor.execute(Action.SetVariable(name = "x", value = "new"), ctx(vars))
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["x"]).isEqualTo("new")
    }
}
