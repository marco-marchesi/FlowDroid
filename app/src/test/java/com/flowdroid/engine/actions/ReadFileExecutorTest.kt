package com.flowdroid.engine.actions

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ReadFileExecutorTest {

    private lateinit var context: Context
    private lateinit var executor: ReadFileExecutor
    private lateinit var automation: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        executor = ReadFileExecutor(context, MagicTextEngineImpl(), TimberStructuredLogger())
        automation = File(context.filesDir, "automation").also { it.mkdirs() }
        automation.listFiles()?.forEach { it.deleteRecursively() }
    }

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

    @Test fun `existing file is read into the variable`() = runTest {
        File(automation, "state.txt").writeText("payload", Charsets.UTF_8)
        val vars = mutableMapOf<String, String>()
        val r = executor.execute(Action.ReadFile(path = "state.txt", intoVar = "x"), ctx(vars))
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(vars["x"]).isEqualTo("payload")
    }

    @Test fun `missing file returns TargetNotFound`() = runTest {
        val r = executor.execute(Action.ReadFile(path = "missing.txt", intoVar = "x"), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.TargetNotFound::class.java)
    }

    @Test fun `path containing dot-dot is rejected`() = runTest {
        val r = executor.execute(Action.ReadFile(path = "../etc/passwd", intoVar = "x"), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `absolute path is rejected`() = runTest {
        val r = executor.execute(Action.ReadFile(path = "/etc/hosts", intoVar = "x"), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `blank intoVar is rejected`() = runTest {
        File(automation, "state.txt").writeText("payload", Charsets.UTF_8)
        val r = executor.execute(Action.ReadFile(path = "state.txt", intoVar = " "), ctx())
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }
}
