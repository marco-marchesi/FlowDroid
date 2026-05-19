package com.flowdroid.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flowdroid.common.ServiceState
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.service.ServiceRegistry
import com.flowdroid.engine.accessibility.AccessibilityControllerImpl
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FlowDroid's [AccessibilityService] entry point — the dispatcher for `UiClick`, `UiSwipe`,
 * `UiTypeText`, and `UiPressKey` actions.
 *
 * Lifecycle:
 *  - The OS instantiates this service when the user enables FlowDroid in
 *    Settings → Accessibility. [onServiceConnected] publishes the live reference to
 *    [AccessibilityControllerImpl] so executors can drive gestures.
 *  - [onUnbind] clears the reference. While unbound, executors fail fast with
 *    `AccessibilityError.ServiceNotBound`.
 *
 * Threading: every accessibility callback runs on a dedicated binder thread. We do NOT block
 * here — heavy work (node-tree walks, sleeps) happens on the controller's own dispatcher.
 *
 * Event listening: we currently consume no events ([onAccessibilityEvent] no-op). Future phases
 * will use TYPE_WINDOW_STATE_CHANGED for the `T-APP-001` app-launch trigger.
 */
@AndroidEntryPoint
class FlowDroidAccessibilityService : AccessibilityService() {

    @Inject lateinit var controller: AccessibilityControllerImpl
    @Inject lateinit var registry: ServiceRegistry
    @Inject lateinit var logger: StructuredLogger

    override fun onServiceConnected() {
        super.onServiceConnected()
        logger.info(TAG, "Accessibility service connected")
        controller.attachService(this)
        registry.setAccessibilityServiceState(ServiceState.RUNNING)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        logger.info(TAG, "Accessibility service unbinding")
        controller.detachService(this)
        registry.setAccessibilityServiceState(ServiceState.STOPPED)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        controller.detachService(this)
        registry.setAccessibilityServiceState(ServiceState.STOPPED)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentional no-op for Phase 2. Reserved for Phase 3 app-launch triggers.
    }

    override fun onInterrupt() {
        logger.warn(TAG, "Accessibility service onInterrupt — pending gestures cancelled by OS")
    }

    // ============================================================================================
    // Internal-facing API consumed by [AccessibilityControllerImpl]. We expose these as `internal`
    // (effectively module-private) so the controller can invoke the service without exposing the
    // raw AccessibilityService machinery to executors.
    // ============================================================================================

    /**
     * Dispatch a synthetic gesture. Caller supplies the strokes; we wrap them in a
     * [GestureDescription] and post via [dispatchGesture]. Returns true once the OS reports
     * completion (or false on cancellation / failure).
     */
    internal fun dispatchPath(
        path: Path,
        startTimeMs: Long,
        durationMs: Long,
        onResult: (Boolean) -> Unit,
    ) {
        try {
            val stroke = GestureDescription.StrokeDescription(path, startTimeMs, durationMs)
            val description = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) = onResult(true)
                    override fun onCancelled(d: GestureDescription?) = onResult(false)
                },
                null,
            )
            if (!ok) {
                logger.warn(TAG, "dispatchGesture returned false")
                onResult(false)
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.error(TAG, "dispatchPath threw", t)
            onResult(false)
        }
    }

    /**
     * Dispatch a two-stroke double-tap gesture. The two strokes overlap slightly with a 50 ms gap
     * between them, which is what the OS recognises as a double-tap.
     */
    internal fun dispatchDoubleTap(x: Float, y: Float, onResult: (Boolean) -> Unit) {
        try {
            val first = pathAt(x, y)
            val second = pathAt(x, y)
            val s1 = GestureDescription.StrokeDescription(first, 0L, 50L)
            val s2 = GestureDescription.StrokeDescription(second, 100L, 50L)
            val description = GestureDescription.Builder().addStroke(s1).addStroke(s2).build()
            val ok = dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) = onResult(true)
                    override fun onCancelled(d: GestureDescription?) = onResult(false)
                },
                null,
            )
            if (!ok) onResult(false)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.error(TAG, "dispatchDoubleTap threw", t)
            onResult(false)
        }
    }

    /** Path of a single point — used by the controller to build tap/long-press strokes. */
    internal fun pathAt(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    /** Two-point path used by [Action.UiSwipe]. */
    internal fun swipePath(x1: Float, y1: Float, x2: Float, y2: Float): Path =
        Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

    internal fun walkRootNode(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        if (t is OutOfMemoryError) throw t
        logger.warn(TAG, "rootInActiveWindow threw", t)
        null
    }

    internal fun globalActionAccessible(actionId: Int): Boolean = try {
        performGlobalAction(actionId)
    } catch (t: Throwable) {
        if (t is OutOfMemoryError) throw t
        logger.warn(TAG, "performGlobalAction($actionId) threw", t)
        false
    }

    /** Set text into a focused editable node. Returns false if there is no focused editable. */
    internal fun setTextOnFocused(text: String): Boolean {
        return try {
            val root = walkRootNode() ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            if (!focused.isEditable) return false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.warn(TAG, "setTextOnFocused threw", t)
            false
        }
    }

    /**
     * Press Enter on the focused editable (e.g. for search fields). Uses the
     * `AccessibilityAction.ACTION_IME_ENTER` introduced in API 30; on older devices we fall back
     * to sending the focused node's IME_ACTION_DONE via a keypress isn't available without root,
     * so we return false and let the caller decide.
     */
    internal fun pressEnterOnFocused(): Boolean {
        return try {
            val root = walkRootNode() ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                focused.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
                )
            } else false
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.warn(TAG, "pressEnterOnFocused threw", t)
            false
        }
    }

    companion object {
        private const val TAG = "Accessibility"
    }
}
