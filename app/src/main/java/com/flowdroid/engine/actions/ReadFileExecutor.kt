package com.flowdroid.engine.actions

import android.content.Context
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.ReadFile]. Reads a file from app-private storage and stores its UTF-8
 * contents in a variable.
 *
 * Sandbox: the file lives at `context.filesDir/automation/<path>`. Path segments containing `..`
 * or starting with `/` are rejected with [ExecutionError.InvalidParam] so flows cannot escape
 * the sandbox to read arbitrary device files.
 *
 * Missing file → [ExecutionError.TargetNotFound]. Read failure → [ExecutionError.SystemFailure].
 */
@Singleton
class ReadFileExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.ReadFile> {

    override val actionClass: Class<Action.ReadFile> = Action.ReadFile::class.java

    override suspend fun execute(
        action: Action.ReadFile,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val resolvedPath = magicText.expandOrEmpty(action.path, context.variables).trim()
        val varName = action.intoVar.trim()
        if (resolvedPath.isBlank()) {
            return Outcome.err(ExecutionError.InvalidParam("path", "resolved to empty"))
        }
        if (varName.isBlank()) {
            return Outcome.err(ExecutionError.InvalidParam("intoVar", "required"))
        }
        if (resolvedPath.contains("..") || resolvedPath.startsWith("/")) {
            return Outcome.err(
                ExecutionError.InvalidParam("path", "must be a relative path inside automation/"),
            )
        }

        val file = File(this.context.filesDir, "automation/$resolvedPath")
        if (!file.exists() || !file.isFile) {
            logger.info(TAG, "file not found",
                "flowId" to context.flow.id, "path" to resolvedPath)
            return Outcome.err(ExecutionError.TargetNotFound("file:$resolvedPath"))
        }

        return try {
            val text = file.readText(Charsets.UTF_8)
            context.variables[varName] = text
            logger.info(TAG, "read file",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "path" to resolvedPath,
                "bytes" to file.length(),
                "intoVar" to varName)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "read failed", t,
                "flowId" to context.flow.id, "path" to resolvedPath)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "read failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.ReadFile"
    }
}
