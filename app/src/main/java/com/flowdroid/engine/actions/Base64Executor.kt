package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.Base64Mode
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.Base64]. RFC 4648 standard alphabet, NO_WRAP encoding (no line breaks).
 *
 * Use `android.util.Base64` rather than `java.util.Base64` so the impl works on API 21 if we
 * ever lower minSdk. Both produce identical output for our settings.
 */
@Singleton
class Base64Executor @Inject constructor(
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Base64> {

    override val actionClass: Class<Action.Base64> = Action.Base64::class.java

    override suspend fun execute(
        action: Action.Base64,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val resolvedInput = magicText.expandOrEmpty(action.input, context.variables)
        val varName = action.intoVar.trim()
        if (varName.isBlank()) {
            return Outcome.err(ExecutionError.InvalidParam("intoVar", "required"))
        }
        return try {
            val out = when (action.mode) {
                Base64Mode.ENCODE -> android.util.Base64.encodeToString(
                    resolvedInput.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                )
                Base64Mode.DECODE -> {
                    val bytes = android.util.Base64.decode(resolvedInput, android.util.Base64.DEFAULT)
                    String(bytes, Charsets.UTF_8)
                }
            }
            context.variables[varName] = out
            logger.info(TAG, "base64 ${action.mode.name.lowercase()}",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "intoVar" to varName,
                "inputLen" to resolvedInput.length,
                "outputLen" to out.length)
            Outcome.ok(Unit)
        } catch (e: IllegalArgumentException) {
            logger.warn(TAG, "decode failed", e,
                "flowId" to context.flow.id, "intoVar" to varName)
            Outcome.err(ExecutionError.InvalidParam("input", "malformed Base64"))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "base64 threw", t,
                "flowId" to context.flow.id, "mode" to action.mode.name)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "base64 failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.Base64"
    }
}
