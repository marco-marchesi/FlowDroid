package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.MathOp
import com.flowdroid.common.flow.expandOrEmpty
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Executor for [Action.Math]. Both sides parsed as Double; non-numeric → 0.0. Result is rendered
 * as an integer string when it has no fractional part (so `5+3` stores `"8"`, not `"8.0"`), else
 * a plain decimal.
 *
 * Division and modulo by zero return 0 with a WARN — the engine never throws.
 */
@Singleton
class MathExecutor @Inject constructor(
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Math> {

    override val actionClass: Class<Action.Math> = Action.Math::class.java

    override suspend fun execute(
        action: Action.Math,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val varName = action.intoVar.trim()
        if (varName.isEmpty()) {
            return Outcome.err(ExecutionError.InvalidParam("intoVar", "required"))
        }
        val l = magicText.expandOrEmpty(action.left, context.variables).toDoubleOrNull() ?: 0.0
        val r = magicText.expandOrEmpty(action.right, context.variables).toDoubleOrNull() ?: 0.0
        val result: Double = when (action.op) {
            MathOp.ADD -> l + r
            MathOp.SUBTRACT -> l - r
            MathOp.MULTIPLY -> l * r
            MathOp.DIVIDE -> if (r == 0.0) {
                logger.warn(TAG, "divide by zero", null, "flowId" to context.flow.id)
                0.0
            } else l / r
            MathOp.MODULO -> if (r == 0.0) {
                logger.warn(TAG, "modulo by zero", null, "flowId" to context.flow.id)
                0.0
            } else l % r
            MathOp.POWER -> l.pow(r)
        }
        val formatted = if (result == result.toLong().toDouble()) result.toLong().toString()
            else result.toString()
        context.variables[varName] = formatted
        logger.info(TAG, "math",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "op" to action.op.name,
            "result" to formatted)
        return Outcome.ok(Unit)
    }

    companion object { private const val TAG = "Action.Math" }
}
