package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.engine.ConditionEvaluator
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.If]. Evaluates [Action.If.condition] against the current execution
 * variables; on `true` runs [Action.If.thenActions], on `false` runs [Action.If.elseActions].
 *
 * Sub-actions share the parent's [ExecutionContext] — variables set inside the branch are
 * visible to subsequent actions in the parent flow. This is the most intuitive behaviour and
 * matches Tasker/Automate semantics.
 *
 * Branch failure handling: the sub-list runs under the same [ActionRunner] contract as the top
 * level. If a sub-action fails without `continueOnError`, the branch short-circuits but the
 * If itself still returns [Outcome.Ok] — the parent flow continues unless the If itself is
 * marked `continueOnError = false` AND the if executor reports an [ExecutionError.SystemFailure],
 * which only happens on genuinely unexpected throws.
 *
 * Why [Lazy] on [ActionRunner]:
 *  - [com.flowdroid.engine.FlowEngineImpl] is both the [ActionRunner] AND the owner of the
 *    `Map<Class<out Action>, ActionExecutor<*>>` that contains this executor. A direct
 *    injection would create a circular Hilt graph; `Lazy` defers resolution to first call.
 */
@Singleton
class IfExecutor @Inject constructor(
    private val runnerLazy: Lazy<ActionRunner>,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.If> {

    override val actionClass: Class<Action.If> = Action.If::class.java

    override suspend fun execute(
        action: Action.If,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        return try {
            val outcome = ConditionEvaluator.evaluate(
                action.condition,
                context.variables,
                magicText,
                logger,
            )
            val branch = if (outcome) action.thenActions else action.elseActions
            logger.info(TAG, "branch",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "result" to outcome,
                "branchSize" to branch.size,
                "op" to action.condition.op.name,
            )
            val runner = runnerLazy.get()
            val stats = runner.runActions(branch, context)
            logger.info(TAG, "branch finished",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "ok" to stats.okCount,
                "err" to stats.errCount,
                "aborted" to stats.aborted,
            )
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "if threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "if failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.If"
    }
}
