package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.StringOp
import com.flowdroid.common.flow.expandOrEmpty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.StringTransform]. Pure function over the resolved `input` string.
 *
 * Op contract:
 *  - `UPPER` / `LOWER` / `TRIM` / `REVERSE`: no args.
 *  - `LENGTH`: writes the integer character count as a string.
 *  - `REPLACE`: arg1 = needle, arg2 = replacement (literal, not regex).
 *  - `SUBSTRING`: arg1 = start index (0-based, clamped), arg2 = end index (exclusive, clamped).
 *    Both args magic-text expanded → parsed as Int; defaults 0 / length.
 */
@Singleton
class StringTransformExecutor @Inject constructor(
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.StringTransform> {

    override val actionClass: Class<Action.StringTransform> = Action.StringTransform::class.java

    override suspend fun execute(
        action: Action.StringTransform,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val varName = action.intoVar.trim()
        if (varName.isEmpty()) {
            return Outcome.err(ExecutionError.InvalidParam("intoVar", "required"))
        }
        val input = magicText.expandOrEmpty(action.input, context.variables)
        val result: String = when (action.op) {
            StringOp.UPPER -> input.uppercase()
            StringOp.LOWER -> input.lowercase()
            StringOp.TRIM -> input.trim()
            StringOp.REVERSE -> input.reversed()
            StringOp.LENGTH -> input.length.toString()
            StringOp.REPLACE -> {
                val needle = magicText.expandOrEmpty(action.arg1, context.variables)
                val replacement = magicText.expandOrEmpty(action.arg2, context.variables)
                if (needle.isEmpty()) input else input.replace(needle, replacement)
            }
            StringOp.SUBSTRING -> {
                val len = input.length
                val startRaw = magicText.expandOrEmpty(action.arg1, context.variables).toIntOrNull() ?: 0
                val endRaw = magicText.expandOrEmpty(action.arg2, context.variables).toIntOrNull() ?: len
                val start = startRaw.coerceIn(0, len)
                val end = endRaw.coerceIn(start, len)
                input.substring(start, end)
            }
        }
        context.variables[varName] = result
        logger.info(TAG, "string transform",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "op" to action.op.name,
            "inLen" to input.length,
            "outLen" to result.length)
        return Outcome.ok(Unit)
    }

    companion object { private const val TAG = "Action.StringTransform" }
}
