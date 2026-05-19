package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.UiTypeText].
 *
 * Resolves the text via magic-text expansion, then asks the [AccessibilityController] to set the
 * text on the currently focused editable node. There is no preceding `ensureScreenOnAndUnlocked`
 * call here because typing implies an editable already has focus — which means the screen is
 * already on and unlocked. We still gate on [AccessibilityController.isReady].
 *
 * Failure modes:
 *  - Service not bound → PermissionMissing.
 *  - No focused editable node → TargetNotFound("focused-editable-node").
 *  - Controller-level gesture/system failure → SystemFailure.
 *  - [OutOfMemoryError] / [kotlin.coroutines.cancellation.CancellationException] are rethrown.
 *
 * Note: the resolved text is not validated for length. Some IMEs reject extremely long
 * `ACTION_SET_TEXT` payloads — surfaced as a controller-level GestureFailed → SystemFailure.
 */
@Singleton
class UiTypeTextExecutor @Inject constructor(
    private val controller: AccessibilityController,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.UiTypeText> {

    override val actionClass: Class<Action.UiTypeText> = Action.UiTypeText::class.java

    override suspend fun execute(
        action: Action.UiTypeText,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val text = magicText.expandOrEmpty(action.text, context.variables)

        logger.info(
            TAG, "ui type text",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "length" to text.length,
            "pressEnterAfter" to action.pressEnterAfter,
        )

        if (!controller.isReady()) {
            logger.warn(
                TAG, "accessibility service not ready",
                null,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
            )
            return Outcome.err(
                ExecutionError.PermissionMissing("BIND_ACCESSIBILITY_SERVICE"),
            )
        }

        return try {
            when (val result = controller.typeText(text, action.pressEnterAfter)) {
                is Outcome.Ok -> {
                    logger.info(
                        TAG, "typeText succeeded",
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "length" to text.length,
                    )
                    Outcome.ok(Unit)
                }
                is Outcome.Err -> {
                    logger.warn(
                        TAG, "typeText failed",
                        result.cause,
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
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
                TAG, "typeText threw",
                t,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
            )
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "typeText threw"), t)
        }
    }

    companion object {
        private const val TAG = "Action.UiType"
    }
}
