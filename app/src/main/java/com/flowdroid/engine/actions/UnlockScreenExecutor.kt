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
 * Executor for [Action.UnlockScreen]. Thin wrapper around
 * [AccessibilityController.ensureScreenOnAndUnlocked] — the heavy lifting (WakeLock + activity
 * launch + KeyguardManager dance) already lives there to support the UI-gesture actions.
 *
 * Exposing it as a standalone action lets the user put `Unlock screen` as their flow's FIRST
 * step independent of any UI gesture — useful when:
 *  - They want the screen on during the rest of the flow (e.g. to see the heads-up notif).
 *  - The flow has both a "click button" step and a "type text" step, and the explicit unlock
 *    makes the intent visible in the flow diagram.
 *
 * Caveat (also surfaced in the editor's supporting text): if a PIN / pattern / password is
 * configured AND the user isn't there to enter it, this action will time out. Android exposes
 * no public API to bypass a configured credential — that's by design.
 */
@Singleton
class UnlockScreenExecutor @Inject constructor(
    private val controller: AccessibilityController,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.UnlockScreen> {

    override val actionClass: Class<Action.UnlockScreen> = Action.UnlockScreen::class.java

    override suspend fun execute(
        action: Action.UnlockScreen,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val timeout = action.timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        return when (val r = controller.ensureScreenOnAndUnlocked(timeout)) {
            is Outcome.Ok -> {
                logger.info(TAG, "unlock ok",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "timeoutMs" to timeout)
                Outcome.ok(Unit)
            }
            is Outcome.Err -> {
                logger.warn(TAG, "unlock failed", null,
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "error" to r.error.toString())
                when (r.error) {
                    is AccessibilityError.Timeout -> Outcome.err(ExecutionError.Timeout(timeout))
                    is AccessibilityError.KeyguardDismissDenied -> Outcome.err(
                        ExecutionError.SystemFailure(
                            "Keyguard dismissal denied — a PIN/pattern/password may be set and " +
                                "the system would not auto-dismiss without user input.",
                        ),
                    )
                    else -> Outcome.err(ExecutionError.SystemFailure(r.error.toString()))
                }
            }
        }
    }

    companion object {
        private const val TAG = "Action.UnlockScreen"
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_TIMEOUT_MS = 15_000L
    }
}
