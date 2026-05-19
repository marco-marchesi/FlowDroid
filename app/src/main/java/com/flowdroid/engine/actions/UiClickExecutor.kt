package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.UiTargetMode
import com.flowdroid.common.flow.expandOrEmpty
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.UiClick].
 *
 * Pre-flight:
 *  1. [AccessibilityController.isReady] — if false, fail fast with [ExecutionError.PermissionMissing]
 *     ("BIND_ACCESSIBILITY_SERVICE"). The user must enable the accessibility service in Settings.
 *  2. [AccessibilityController.ensureScreenOnAndUnlocked] — the accessibility node tree is empty
 *     while the lock screen is up, so we wake/unlock first. Errors are mapped via
 *     [toExecutionError].
 *
 * Dispatch:
 *  - [UiTargetMode.COORDINATES] → [AccessibilityController.tap].
 *  - any other mode → resolves the appropriate needle (text / contentDescription / viewId) via
 *    magic-text expansion, then calls [AccessibilityController.clickByNode]. A blank needle is an
 *    [ExecutionError.InvalidParam].
 *
 * Failure modes:
 *  - Accessibility service not bound → PermissionMissing.
 *  - Keyguard not dismissable → PermissionMissing (DISMISS_KEYGUARD).
 *  - Node not found / timeout → TargetNotFound / Timeout (from controller).
 *  - Invalid needle → InvalidParam.
 *  - [OutOfMemoryError] / [kotlin.coroutines.cancellation.CancellationException] are rethrown.
 */
@Singleton
class UiClickExecutor @Inject constructor(
    private val controller: AccessibilityController,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.UiClick> {

    override val actionClass: Class<Action.UiClick> = Action.UiClick::class.java

    override suspend fun execute(
        action: Action.UiClick,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        logger.info(
            TAG, "ui click",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "targetMode" to action.targetMode.name,
            "clickMode" to action.clickMode.name,
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
            val unlock = controller.ensureScreenOnAndUnlocked()
            if (unlock is Outcome.Err) {
                logger.warn(
                    TAG, "screen-on/unlock failed",
                    unlock.cause,
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "reason" to unlock.error.toString(),
                )
                return Outcome.err(unlock.error.toExecutionError(), unlock.cause)
            }

            val result = when (action.targetMode) {
                UiTargetMode.COORDINATES -> controller.tap(
                    x = action.x,
                    y = action.y,
                    mode = action.clickMode,
                    holdMs = action.longPressDurationMs,
                )
                else -> {
                    val rawNeedle: String? = when (action.targetMode) {
                        UiTargetMode.BY_TEXT -> action.text
                        UiTargetMode.BY_CONTENT_DESCRIPTION -> action.contentDescription
                        UiTargetMode.BY_VIEW_ID -> action.viewId
                        UiTargetMode.COORDINATES -> null // unreachable
                    }
                    val needle = rawNeedle
                        ?.let { magicText.expandOrEmpty(it, context.variables) }
                        ?.takeIf { it.isNotBlank() }
                        ?: return Outcome.err(
                            ExecutionError.InvalidParam(
                                name = action.targetMode.name.lowercase(),
                                reason = "needle resolved to empty",
                            ),
                        )
                    controller.clickByNode(
                        mode = action.targetMode,
                        needle = needle,
                        textMatch = action.textMatch,
                        inPackage = action.inPackage,
                        clickMode = action.clickMode,
                        longPressDurationMs = action.longPressDurationMs,
                        timeoutMs = action.timeoutMs,
                    )
                }
            }

            when (result) {
                is Outcome.Ok -> {
                    logger.info(
                        TAG, "ui click succeeded",
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "targetMode" to action.targetMode.name,
                    )
                    Outcome.ok(Unit)
                }
                is Outcome.Err -> {
                    logger.warn(
                        TAG, "ui click failed",
                        result.cause,
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "targetMode" to action.targetMode.name,
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
                TAG, "ui click threw",
                t,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
            )
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "ui click threw"), t)
        }
    }

    companion object {
        private const val TAG = "Action.UiClick"
    }
}
