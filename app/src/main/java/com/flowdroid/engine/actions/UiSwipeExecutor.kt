package com.flowdroid.engine.actions

import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.SwipeDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.UiSwipe].
 *
 * Pre-flight is identical to [UiClickExecutor]: service readiness check + screen-on/unlock.
 *
 * Path selection:
 *  - If [Action.UiSwipe.direction] is non-null, a half-screen swipe is computed starting at the
 *    screen centre, with the endpoint a quarter of the screen away in each of x/y. Screen size
 *    is read from [WindowManager.getCurrentWindowMetrics] on API 30+, falling back to the
 *    deprecated `defaultDisplay.getRealSize` for older devices. If both fail we fall back to
 *    1080×2400 and log a WARN — this is a safety net, the device should always supply metrics.
 *  - Otherwise the raw `from*`/`to*` coordinates are used verbatim.
 *
 * Failure modes:
 *  - Service not ready → PermissionMissing.
 *  - Keyguard dismissal denied → PermissionMissing.
 *  - Underlying gesture failure → SystemFailure.
 *  - [OutOfMemoryError] / [kotlin.coroutines.cancellation.CancellationException] are rethrown.
 */
@Singleton
class UiSwipeExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: AccessibilityController,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.UiSwipe> {

    override val actionClass: Class<Action.UiSwipe> = Action.UiSwipe::class.java

    override suspend fun execute(
        action: Action.UiSwipe,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        logger.info(
            TAG, "ui swipe",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "direction" to (action.direction?.name ?: "<coords>"),
            "durationMs" to action.durationMs,
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

            val (fromX, fromY, toX, toY) = if (action.direction != null) {
                computeDirectionalSwipe(action.direction, context.flow.id, context.executionId)
            } else {
                SwipePoints(action.fromX, action.fromY, action.toX, action.toY)
            }

            val result = controller.swipe(
                fromX = fromX,
                fromY = fromY,
                toX = toX,
                toY = toY,
                durationMs = action.durationMs,
            )

            when (result) {
                is Outcome.Ok -> {
                    logger.info(
                        TAG, "swipe complete",
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "from" to "($fromX,$fromY)",
                        "to" to "($toX,$toY)",
                    )
                    Outcome.ok(Unit)
                }
                is Outcome.Err -> {
                    logger.warn(
                        TAG, "swipe failed",
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
                TAG, "swipe threw",
                t,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
            )
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "swipe threw"), t)
        }
    }

    private data class SwipePoints(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    private fun computeDirectionalSwipe(
        direction: SwipeDirection,
        flowId: String,
        execId: String,
    ): SwipePoints {
        val size = screenSize() ?: run {
            logger.warn(
                TAG, "screen metrics unavailable; falling back to 1080x2400",
                null,
                "flowId" to flowId,
                "execId" to execId,
            )
            Point(DEFAULT_W, DEFAULT_H)
        }
        val cx = size.x / 2f
        val cy = size.y / 2f
        // "Half-screen swipe" — quarter-screen offset in each direction from centre yields a
        // half-screen total travel.
        val dx = size.x / 4f
        val dy = size.y / 4f
        return when (direction) {
            SwipeDirection.UP -> SwipePoints(cx, cy + dy, cx, cy - dy)
            SwipeDirection.DOWN -> SwipePoints(cx, cy - dy, cx, cy + dy)
            SwipeDirection.LEFT -> SwipePoints(cx + dx, cy, cx - dx, cy)
            SwipeDirection.RIGHT -> SwipePoints(cx - dx, cy, cx + dx, cy)
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point? = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            p
        }
    } catch (t: Throwable) {
        if (t is OutOfMemoryError ||
            t is kotlin.coroutines.cancellation.CancellationException
        ) throw t
        null
    }

    companion object {
        private const val TAG = "Action.UiSwipe"
        private const val DEFAULT_W = 1080
        private const val DEFAULT_H = 2400
    }
}
