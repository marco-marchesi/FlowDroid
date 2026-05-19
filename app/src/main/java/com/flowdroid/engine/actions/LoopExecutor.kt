package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.LoopMode
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.Loop]. Same recursion contract as [IfExecutor] — uses
 * [dagger.Lazy] on [ActionRunner] to break the engine's circular Hilt graph.
 *
 * Semantics:
 *  - `COUNT`: run the sub-list exactly `count` times (clamped to `[0, MAX_ITERATIONS]`). 0 is a
 *    valid no-op (lets users write conditional iteration with magic-text-driven counts).
 *  - `FOREACH_LINES`: expand `list` via magic text, split on `\n`, drop blank lines after `trim()`,
 *    iterate. Each iteration writes `{var.<itemVar>}` and `{var.<indexVar>}`.
 *
 * Failure handling: if a sub-action fails without `continueOnError`, the runner aborts THAT
 * iteration. The loop then decides what to do based on the Loop's own `continueOnError`:
 *  - true (default for safety in user-edited flows): move on to the next iteration.
 *  - false: short-circuit and abort the rest of the loop. The Loop action itself still returns
 *    Ok — only an unexpected throw bubbles up as SystemFailure.
 */
@Singleton
class LoopExecutor @Inject constructor(
    private val runnerLazy: Lazy<ActionRunner>,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Loop> {

    override val actionClass: Class<Action.Loop> = Action.Loop::class.java

    override suspend fun execute(
        action: Action.Loop,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        return try {
            val items: List<String> = when (action.mode) {
                LoopMode.COUNT -> {
                    val raw = action.count
                    if (raw < 0) {
                        return Outcome.err(
                            ExecutionError.InvalidParam("count", "must be ≥ 0 (was $raw)"),
                        )
                    }
                    val capped = raw.coerceAtMost(Action.Loop.MAX_ITERATIONS)
                    if (capped < raw) {
                        logger.warn(TAG, "loop count clamped to MAX_ITERATIONS", null,
                            "flowId" to context.flow.id,
                            "requested" to raw,
                            "max" to Action.Loop.MAX_ITERATIONS)
                    }
                    List(capped) { "" }
                }
                LoopMode.FOREACH_LINES -> {
                    val expanded = magicText.expandOrEmpty(action.list, context.variables)
                    val all = expanded.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    if (all.size > Action.Loop.MAX_ITERATIONS) {
                        logger.warn(TAG, "foreach truncated to MAX_ITERATIONS", null,
                            "flowId" to context.flow.id,
                            "size" to all.size,
                            "max" to Action.Loop.MAX_ITERATIONS)
                        all.take(Action.Loop.MAX_ITERATIONS)
                    } else all
                }
            }

            logger.info(TAG, "loop start",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "mode" to action.mode.name,
                "iterations" to items.size,
            )

            val runner = runnerLazy.get()
            val itemVar = action.itemVar.ifBlank { "item" }
            val indexVar = action.indexVar.ifBlank { "index" }

            var iterationOk = 0
            var iterationAborted = 0
            for ((index, item) in items.withIndex()) {
                context.variables[itemVar] = item
                context.variables[indexVar] = index.toString()
                val stats = runner.runActions(action.actions, context)
                if (stats.aborted) {
                    iterationAborted++
                    logger.info(TAG, "loop iteration aborted",
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "index" to index,
                    )
                    if (!action.continueOnError) {
                        logger.warn(TAG, "loop short-circuit (continueOnError=false)", null,
                            "flowId" to context.flow.id,
                            "execId" to context.executionId,
                        )
                        break
                    }
                } else {
                    iterationOk++
                }
            }

            logger.info(TAG, "loop finished",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "ok" to iterationOk,
                "aborted" to iterationAborted,
            )
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "loop threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "loop failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.Loop"
    }
}
