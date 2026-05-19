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
import com.flowdroid.common.flow.FileWriteMode
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
class WriteFileExecutorTest {

    private lateinit var context: Context
    private lateinit var executor: WriteFileExecutor
    private lateinit var automation: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        executor = WriteFileExecutor(context, MagicTextEngineImpl(), TimberStructuredLogger())
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

    @Test fun `overwrite writes content to a new file with magic-text expansion`() = runTest {
        val vars = mutableMapOf("who" to "alice")
        val r = executor.execute(
            Action.WriteFile(
                path = "greet.txt",
                content = "hello {who}",
                mode = FileWriteMode.OVERWRITE,
            ),
            ctx(vars),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(File(automation, "greet.txt").readText()).isEqualTo("hello alice")
    }

    @Test fun `append adds to an existing file without truncating`() = runTest {
        val file = File(automation, "log.txt").also { it.writeText("a\n") }
        val r = executor.execute(
            Action.WriteFile(path = "log.txt", content = "b\n", mode = FileWriteMode.APPEND),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(file.readText()).isEqualTo("a\nb\n")
    }

    @Test fun `parent directories are created automatically`() = runTest {
        val r = executor.execute(
            Action.WriteFile(
                path = "sub/dir/state.txt",
                content = "x",
                mode = FileWriteMode.OVERWRITE,
            ),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(File(automation, "sub/dir/state.txt").readText()).isEqualTo("x")
    }

    @Test fun `dot-dot path is rejected`() = runTest {
        val r = executor.execute(
            Action.WriteFile(
                path = "../escape.txt",
                content = "x",
                mode = FileWriteMode.OVERWRITE,
            ),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }

    @Test fun `absolute path is rejected`() = runTest {
        val r = executor.execute(
            Action.WriteFile(
                path = "/sdcard/x.txt",
                content = "x",
                mode = FileWriteMode.OVERWRITE,
            ),
            ctx(),
        )
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ExecutionError.InvalidParam::class.java)
    }
}
