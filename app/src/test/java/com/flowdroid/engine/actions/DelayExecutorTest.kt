package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelayExecutorTest {

    private val logger = TimberStructuredLogger()
    private val executor = DelayExecutor(logger)

    private fun ctx() = ExecutionContext(
        executionId = "exec1",
        flow = Flow(
            id = "flow-1",
            name = "F",
            enabled = true,
            triggers = listOf(Trigger.NotificationPosted()),
            actions = emptyList(),
            createdAt = 0L,
            updatedAt = 0L,
        ),
        triggerEvent = TriggerEvent.NotificationFired(
            event = com.flowdroid.common.domain.NotificationEvent(
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

    @Test fun `delays for the requested duration`() = runTest {
        var completed = false
        val job = launch {
            val r = executor.execute(Action.Delay(millis = 500L), ctx())
            assertThat(r).isInstanceOf(Outcome.Ok::class.java)
            completed = true
        }
        runCurrent()
        assertThat(completed).isFalse()
        advanceTimeBy(499L)
        runCurrent()
        assertThat(completed).isFalse()
        advanceTimeBy(2L)
        runCurrent()
        assertThat(completed).isTrue()
        job.join()
    }

    @Test fun `zero millis returns Ok immediately`() = runTest {
        val r = executor.execute(Action.Delay(millis = 0L), ctx())
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test fun `negative millis yields InvalidParam`() = runTest {
        val r = executor.execute(Action.Delay(millis = -1L), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }
}
