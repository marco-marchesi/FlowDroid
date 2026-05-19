package com.flowdroid.engine.actions

import android.os.Build
import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Base64Mode
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.engine.MagicTextEngineImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class Base64ExecutorTest {

    private val executor = Base64Executor(MagicTextEngineImpl(), TimberStructuredLogger())

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

    @Test fun `encode produces the standard base64 of the UTF-8 bytes`() = runTest {
        val vars = mutableMapOf<String, String>()
        val r = executor.execute(
            Action.Base64(input = "ciao", mode = Base64Mode.ENCODE, intoVar = "b"),
            ctx(vars),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["b"]).isEqualTo("Y2lhbw==")
    }

    @Test fun `decode is the inverse of encode for round-trip`() = runTest {
        val vars = mutableMapOf<String, String>()
        executor.execute(
            Action.Base64(input = "hello world", mode = Base64Mode.ENCODE, intoVar = "enc"),
            ctx(vars),
        )
        val r = executor.execute(
            Action.Base64(input = vars["enc"]!!, mode = Base64Mode.DECODE, intoVar = "dec"),
            ctx(vars),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["dec"]).isEqualTo("hello world")
    }

    @Test fun `decode rejects malformed input with InvalidParam`() = runTest {
        val r = executor.execute(
            Action.Base64(input = "@@@not-base64@@@", mode = Base64Mode.DECODE, intoVar = "x"),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `blank intoVar is rejected`() = runTest {
        val r = executor.execute(
            Action.Base64(input = "x", mode = Base64Mode.ENCODE, intoVar = "  "),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }
}
