package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.SetVariable] — assigns a magic-text-resolved string to a named variable
 * in the execution scope. Used to:
 *  - extract structured data from an HTTP response, e.g.
 *    `Action.SetVariable("match_id", "{var.response_body|jsonpath:$.matches[0].id}")`.
 *  - lift a notification field into a friendlier name for downstream actions.
 *  - store an intermediate computation for reuse across several subsequent actions.
 *
 * The variable name itself is literal — magic text is NOT applied to it. The value is magic-text
 * expanded once at execution time.
 *
 * Failure modes:
 *  - blank name → [ExecutionError.InvalidParam]
 *  - everything else logs a warning and proceeds with the empty string (per magic-text contract).
 */
@Singleton
class SetVariableExecutor @Inject constructor(
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.SetVariable> {

    override val actionClass: Class<Action.SetVariable> = Action.SetVariable::class.java

    override suspend fun execute(
        action: Action.SetVariable,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val name = action.name.trim()
        if (name.isBlank()) {
            logger.warn(TAG, "blank variable name",
                null,
                "flowId" to context.flow.id, "execId" to context.executionId)
            return Outcome.err(ExecutionError.InvalidParam("name", "required"))
        }
        val resolved = try {
            magicText.expandOrEmpty(action.value, context.variables)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "value expand failed", t,
                "flowId" to context.flow.id, "name" to name)
            ""
        }
        context.variables[name] = resolved
        logger.info(
            TAG, "set variable",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "name" to name,
            "valueLen" to resolved.length,
        )
        return Outcome.ok(Unit)
    }

    companion object {
        private const val TAG = "Action.SetVariable"
    }
}
