package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.AccessibilityError
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.LockScreen]. Calls into the accessibility controller's lock-screen
 * helper, which performs `AccessibilityService.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`
 * (Android 9+ — we target 29+ so always available). The user must have granted the
 * Accessibility permission already; otherwise we return SystemFailure.
 */
@Singleton
class LockScreenExecutor @Inject constructor(
    private val controller: AccessibilityController,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.LockScreen> {

    override val actionClass: Class<Action.LockScreen> = Action.LockScreen::class.java

    override suspend fun execute(
        action: Action.LockScreen,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        return when (val r = controller.lockScreen()) {
            is Outcome.Ok -> {
                logger.info(TAG, "screen locked",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId)
                Outcome.ok(Unit)
            }
            is Outcome.Err -> {
                logger.warn(TAG, "lock failed", null,
                    "flowId" to context.flow.id,
                    "error" to r.error.toString())
                when (r.error) {
                    is AccessibilityError.ServiceNotBound,
                    is AccessibilityError.PermissionMissing -> Outcome.err(
                        ExecutionError.SystemFailure(
                            "Accessibility service not bound — grant Accessibility in Setup.",
                        ),
                    )
                    else -> Outcome.err(ExecutionError.SystemFailure(r.error.toString()))
                }
            }
        }
    }

    companion object { private const val TAG = "Action.LockScreen" }
}
