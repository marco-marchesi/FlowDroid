package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.Delay].
 *
 * Simply suspends the executing coroutine for [Action.Delay.millis] milliseconds via
 * [kotlinx.coroutines.delay]. Cancellation propagates naturally — if the engine cancels the
 * execution (e.g. flow stop, app shutdown) the [delay] throws [kotlin.coroutines.cancellation.CancellationException]
 * which we rethrow per project convention.
 *
 * Timeout interaction: the FlowEngine wraps every action in a 30 s per-action timeout. A Delay
 * longer than 30 s will therefore surface as [ExecutionError.Timeout] from the engine, not from
 * here. That is the intended behaviour — we do not work around the engine's safety net.
 *
 * Validation:
 *  - Negative `millis` → [ExecutionError.InvalidParam] (kotlinx.coroutines.delay accepts negative
 *    values as no-op but we want the user's flow to surface the bug rather than silently skip).
 *  - We log a WARN if `millis > 30_000` flagging that the engine's per-action timeout will fire
 *    first; we do NOT clamp — the engine is responsible for enforcing the timeout.
 *
 * Failure modes:
 *  - [ExecutionError.InvalidParam] when `millis < 0`.
 *  - Any other unexpected throwable → [ExecutionError.SystemFailure].
 *  - [OutOfMemoryError] and [kotlin.coroutines.cancellation.CancellationException] are rethrown.
 */
@Singleton
class DelayExecutor @Inject constructor(
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Delay> {

    override val actionClass: Class<Action.Delay> = Action.Delay::class.java

    override suspend fun execute(
        action: Action.Delay,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        if (action.millis < 0) {
            logger.warn(
                TAG, "negative delay rejected",
                null,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "millis" to action.millis,
            )
            return Outcome.err(
                ExecutionError.InvalidParam("millis", "must be >= 0, got ${action.millis}"),
            )
        }

        logger.info(
            TAG, "delaying",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "millis" to action.millis,
        )
        if (action.millis > ENGINE_TIMEOUT_HINT_MS) {
            logger.warn(
                TAG, "delay longer than engine per-action timeout; engine timeout will fire first",
                null,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "millis" to action.millis,
                "timeoutHintMs" to ENGINE_TIMEOUT_HINT_MS,
            )
        }

        return try {
            delay(action.millis)
            logger.info(
                TAG, "delay complete",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "millis" to action.millis,
            )
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(
                TAG, "delay failed",
                t,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "millis" to action.millis,
            )
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "delay failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.Delay"
        /** Engine's per-action timeout (informational; the engine owns the actual enforcement). */
        private const val ENGINE_TIMEOUT_HINT_MS = 30_000L
    }
}
