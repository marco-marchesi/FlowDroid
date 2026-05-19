package com.flowdroid.engine.actions

import android.content.Context
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.FileWriteMode
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.WriteFile]. Writes magic-text-expanded content into app-private storage.
 * Same sandbox as [ReadFileExecutor] — files live under `context.filesDir/automation/`.
 *
 * Modes:
 *  - `OVERWRITE`: replaces the file content.
 *  - `APPEND`: appends without leading newline. The user can include `\n` in content to control.
 */
@Singleton
class WriteFileExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.WriteFile> {

    override val actionClass: Class<Action.WriteFile> = Action.WriteFile::class.java

    override suspend fun execute(
        action: Action.WriteFile,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val resolvedPath = magicText.expandOrEmpty(action.path, context.variables).trim()
        if (resolvedPath.isBlank()) {
            return Outcome.err(ExecutionError.InvalidParam("path", "resolved to empty"))
        }
        if (resolvedPath.contains("..") || resolvedPath.startsWith("/")) {
            return Outcome.err(
                ExecutionError.InvalidParam("path", "must be a relative path inside automation/"),
            )
        }
        val resolvedContent = magicText.expandOrEmpty(action.content, context.variables)
        val file = File(this.context.filesDir, "automation/$resolvedPath")
        return try {
            file.parentFile?.mkdirs()
            when (action.mode) {
                FileWriteMode.OVERWRITE -> file.writeText(resolvedContent, Charsets.UTF_8)
                FileWriteMode.APPEND -> file.appendText(resolvedContent, Charsets.UTF_8)
            }
            logger.info(TAG, "wrote file",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "path" to resolvedPath,
                "mode" to action.mode.name,
                "bytes" to resolvedContent.toByteArray(Charsets.UTF_8).size)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "write failed", t,
                "flowId" to context.flow.id, "path" to resolvedPath)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "write failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.WriteFile"
    }
}
