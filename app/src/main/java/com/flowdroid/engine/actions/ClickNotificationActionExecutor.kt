package com.flowdroid.engine.actions

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.SbnCache
import com.flowdroid.common.flow.TriggerEvent
import java.util.regex.PatternSyntaxException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.ClickNotificationAction] — the "click a notification's action button" primitive.
 *
 * Flow:
 *  1. The trigger event must be a [TriggerEvent.NotificationFired] — otherwise we have no SBN
 *     to operate on.
 *  2. Look up the live [com.flowdroid.common.flow.SbnHandle] in the cache by SBN key. If it has
 *     been evicted (rare for sub-second flows, possible if the engine is slow), fail with
 *     [ExecutionError.TargetNotFound].
 *  3. Compile the user's label regex. Pattern syntax errors become [ExecutionError.InvalidParam].
 *  4. Walk the SBN's action labels and find the first one whose label `containsMatchIn` matches.
 *     Failure to find one → [ExecutionError.TargetNotFound], with the available labels logged
 *     so the user can fix their flow.
 *  5. Fire the action via the handle's `fireAction` lambda. A `false` return is treated as a
 *     [ExecutionError.SystemFailure] — Android refused to send the PendingIntent.
 *  6. If `dismissAfter` is true, also dismiss the notification. Dismissal failure is **not**
 *     fatal — we still consider the click successful. Logged at INFO so it shows up in audit
 *     trails.
 */
@Singleton
class ClickNotificationActionExecutor @Inject constructor(
    private val sbnCache: SbnCache,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.ClickNotificationAction> {

    override val actionClass: Class<Action.ClickNotificationAction> =
        Action.ClickNotificationAction::class.java

    override suspend fun execute(
        action: Action.ClickNotificationAction,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {

        val notif = context.triggerEvent as? TriggerEvent.NotificationFired
            ?: return Outcome.err(
                ExecutionError.InvalidParam(
                    "trigger",
                    "ClickNotificationAction requires a notification trigger",
                )
            )

        val handle = sbnCache.get(notif.sbnKey)
        if (handle == null) {
            logger.warn(
                TAG, "sbn handle missing (evicted or never cached)",
                null,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "sbnKey" to notif.sbnKey,
            )
            return Outcome.err(ExecutionError.TargetNotFound("sbn:${notif.sbnKey}"))
        }

        val regex: Regex = try {
            Regex(action.labelRegex)
        } catch (e: PatternSyntaxException) {
            return Outcome.err(
                ExecutionError.InvalidParam("labelRegex", e.message ?: "invalid regex"),
                e,
            )
        }

        val labels = handle.getActionLabels()
        val index = labels.indexOfFirst { regex.containsMatchIn(it) }
        if (index < 0) {
            logger.info(
                TAG, "no action label matched",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "pattern" to action.labelRegex,
                "availableLabels" to labels.joinToString("|"),
            )
            return Outcome.err(ExecutionError.TargetNotFound("action-label"))
        }

        val fired: Boolean = try {
            handle.fireAction(index)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(
                TAG, "fireAction threw",
                t,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
            )
            return Outcome.err(
                ExecutionError.SystemFailure(t.message ?: "fireAction threw"),
                t,
            )
        }

        if (!fired) {
            return Outcome.err(ExecutionError.SystemFailure("fireAction returned false"))
        }

        if (action.dismissAfter) {
            // Best-effort: dismissal failure does not propagate as an action failure.
            val dismissed = try {
                handle.dismiss()
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                logger.warn(
                    TAG, "dismiss threw (ignored)",
                    t,
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                )
                false
            }
            if (!dismissed) {
                logger.info(
                    TAG, "dismiss returned false (ignored)",
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                )
            }
        }

        return Outcome.ok(Unit)
    }

    companion object {
        private const val TAG = "Action.Click"
    }
}
