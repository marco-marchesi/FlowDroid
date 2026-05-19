package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.UiPressKey].
 *
 * Delegates straight to [AccessibilityController.pressKey]. No screen-on/unlock guard because
 * some keys (e.g. POWER_DIALOG, LOCK_SCREEN, HOME) are explicitly used to manage the locked/off
 * state — guarding here would either be tautological or actively harmful.
 *
 * Failure modes:
 *  - Service not bound → PermissionMissing("BIND_ACCESSIBILITY_SERVICE").
 *  - Controller-level GestureFailed/SystemFailure → SystemFailure.
 *  - [OutOfMemoryError] / [kotlin.coroutines.cancellation.CancellationException] are rethrown.
 */
@Singleton
class UiPressKeyExecutor @Inject constructor(
    private val controller: AccessibilityController,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.UiPressKey> {

    override val actionClass: Class<Action.UiPressKey> = Action.UiPressKey::class.java

    override suspend fun execute(
        action: Action.UiPressKey,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        logger.info(
            TAG, "ui press key",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "key" to action.key.name,
        )

        if (!controller.isReady()) {
            logger.warn(
                TAG, "accessibility service not ready",
                null,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "key" to action.key.name,
            )
            return Outcome.err(
                ExecutionError.PermissionMissing("BIND_ACCESSIBILITY_SERVICE"),
            )
        }

        return try {
            when (val result = controller.pressKey(action.key)) {
                is Outcome.Ok -> {
                    logger.info(
                        TAG, "pressKey succeeded",
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "key" to action.key.name,
                    )
                    Outcome.ok(Unit)
                }
                is Outcome.Err -> {
                    logger.warn(
                        TAG, "pressKey failed",
                        result.cause,
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "key" to action.key.name,
                        "reason" to result.error.toString(),
                    )
                    Outcome.err(result.error.toExecutionError(), result.cause)
                }
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(
                TAG, "pressKey threw",
                t,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "key" to action.key.name,
            )
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "pressKey threw"), t)
        }
    }

    companion object {
        private const val TAG = "Action.UiKey"
    }
}
