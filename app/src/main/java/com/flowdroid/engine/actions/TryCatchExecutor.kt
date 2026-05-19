package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.TryCatch].
 *
 * Decision rule for which branch runs:
 *  1. Try always runs first.
 *  2. Catch runs iff the try path reported any failure (`aborted == true` OR `errCount > 0`).
 *  3. Finally always runs after either try-only or try-then-catch, regardless of outcome.
 *
 * The TryCatch itself returns `Outcome.Ok` unless an unexpected throw escapes; that's the whole
 * point — it's the surface that absorbs sub-failures.
 *
 * If [Action.TryCatch.intoErrorVar] is non-blank, the engine writes a short reason string to
 * `{var.<intoErrorVar>}` before invoking catch. Currently the reason is a coarse-grained tag
 * derived from the try's [ActionRunner.RunStats]; a richer error model can be layered on later
 * without changing this contract.
 */
@Singleton
class TryCatchExecutor @Inject constructor(
    private val runnerLazy: Lazy<ActionRunner>,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.TryCatch> {

    override val actionClass: Class<Action.TryCatch> = Action.TryCatch::class.java

    override suspend fun execute(
        action: Action.TryCatch,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        return try {
            val runner = runnerLazy.get()
            logger.info(TAG, "try start",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "trySize" to action.tryActions.size,
            )
            val tryStats = runner.runActions(action.tryActions, context)
            val tryFailed = tryStats.aborted || tryStats.errCount > 0
            logger.info(TAG, "try finished",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "ok" to tryStats.okCount,
                "err" to tryStats.errCount,
                "aborted" to tryStats.aborted,
                "failed" to tryFailed,
            )

            if (tryFailed) {
                val reason = if (tryStats.aborted) "action_aborted" else "errors=${tryStats.errCount}"
                val varName = action.intoErrorVar.trim()
                if (varName.isNotEmpty()) {
                    context.variables[varName] = reason
                }
                logger.info(TAG, "catch start",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "reason" to reason,
                    "catchSize" to action.catchActions.size,
                )
                val catchStats = runner.runActions(action.catchActions, context)
                logger.info(TAG, "catch finished",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "ok" to catchStats.okCount,
                    "err" to catchStats.errCount,
                    "aborted" to catchStats.aborted,
                )
            }

            if (action.finallyActions.isNotEmpty()) {
                logger.info(TAG, "finally start",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "finallySize" to action.finallyActions.size,
                )
                val finallyStats = runner.runActions(action.finallyActions, context)
                logger.info(TAG, "finally finished",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "ok" to finallyStats.okCount,
                    "err" to finallyStats.errCount,
                    "aborted" to finallyStats.aborted,
                )
            }

            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "try-catch threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "try-catch failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.TryCatch"
    }
}
