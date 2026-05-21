package com.flowdroid.engine.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.AccessibilityController
import com.flowdroid.common.flow.AccessibilityError
import com.flowdroid.common.flow.ClickMode
import com.flowdroid.common.flow.TextMatch
import com.flowdroid.common.flow.UiKey
import com.flowdroid.common.flow.UiTargetMode
import com.flowdroid.service.FlowDroidAccessibilityService
import com.flowdroid.service.UnlockBridge
import com.flowdroid.service.UnlockHelperActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Implementation of [AccessibilityController] that drives the live
 * [FlowDroidAccessibilityService] when the user has enabled it.
 *
 * Lifecycle:
 *  - On `onServiceConnected` the service calls [attachService]; on `onUnbind` it calls
 *    [detachService]. Between those callbacks executors observe [isReady] = true.
 *  - The reference is held via [AtomicReference] so reads and writes are lock-free.
 *
 * Every coroutine-suspending call returns an [Outcome]; we never throw to the caller.
 */
@Singleton
class AccessibilityControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StructuredLogger,
) : AccessibilityController {

    private val serviceRef = AtomicReference<FlowDroidAccessibilityService?>()

    fun attachService(service: FlowDroidAccessibilityService) {
        serviceRef.set(service)
        logger.info(TAG, "accessibility service attached")
    }

    fun detachService(service: FlowDroidAccessibilityService) {
        // Only clear if it's the same instance — defensive against double-bind.
        serviceRef.compareAndSet(service, null)
        logger.info(TAG, "accessibility service detached")
    }

    override fun isReady(): Boolean = serviceRef.get() != null

    /** Helper that runs [block] only if the service is bound, otherwise returns ServiceNotBound. */
    private suspend inline fun <T> withService(
        block: (FlowDroidAccessibilityService) -> Outcome<T, AccessibilityError>,
    ): Outcome<T, AccessibilityError> {
        val s = serviceRef.get()
            ?: return Outcome.err(AccessibilityError.ServiceNotBound)
        return block(s)
    }

    // ============================================================================================
    // Gestures
    // ============================================================================================

    override suspend fun tap(
        x: Float, y: Float, mode: ClickMode, holdMs: Long,
    ): Outcome<Unit, AccessibilityError> = withService { s ->
        when (mode) {
            ClickMode.SINGLE -> dispatchSingleStroke(s, s.pathAt(x, y), 0L, 50L)
            ClickMode.LONG -> dispatchSingleStroke(
                s, s.pathAt(x, y), 0L,
                holdMs.coerceAtLeast(100L).coerceAtMost(60_000L),
            )
            ClickMode.DOUBLE -> dispatchDoubleTap(s, x, y)
        }
    }

    override suspend fun swipe(
        fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long,
    ): Outcome<Unit, AccessibilityError> = withService { s ->
        dispatchSingleStroke(
            s, s.swipePath(fromX, fromY, toX, toY),
            startTimeMs = 0L,
            durationMs = durationMs.coerceAtLeast(50L).coerceAtMost(60_000L),
        )
    }

    override suspend fun clickByNode(
        mode: UiTargetMode,
        needle: String,
        textMatch: TextMatch,
        inPackage: String?,
        clickMode: ClickMode,
        longPressDurationMs: Long,
        timeoutMs: Long,
    ): Outcome<Unit, AccessibilityError> = withService { s ->
        val found = withTimeoutOrNull(timeoutMs.coerceAtLeast(100L)) {
            pollForNode(s, mode, needle, textMatch, inPackage)
        }
        if (found == null) {
            logger.info(
                TAG, "node not found in window",
                "mode" to mode.name, "needle" to needle, "inPackage" to (inPackage ?: "*"),
            )
            return@withService Outcome.err(AccessibilityError.NodeNotFound(mode, needle))
        }
        // Prefer ACTION_CLICK on the node itself; fall back to gesture at bounds centre if the
        // node isn't clickable.
        val performed = when (clickMode) {
            ClickMode.SINGLE -> if (found.isClickable) {
                found.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val bounds = android.graphics.Rect().also { found.getBoundsInScreen(it) }
                dispatchSingleStroke(
                    s, s.pathAt(bounds.exactCenterX(), bounds.exactCenterY()), 0L, 50L,
                ).isOk()
            }
            ClickMode.LONG -> if (found.isLongClickable) {
                found.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } else {
                val bounds = android.graphics.Rect().also { found.getBoundsInScreen(it) }
                dispatchSingleStroke(
                    s, s.pathAt(bounds.exactCenterX(), bounds.exactCenterY()), 0L,
                    longPressDurationMs.coerceAtLeast(100L),
                ).isOk()
            }
            ClickMode.DOUBLE -> {
                val bounds = android.graphics.Rect().also { found.getBoundsInScreen(it) }
                dispatchDoubleTap(s, bounds.exactCenterX(), bounds.exactCenterY()).isOk()
            }
        }
        if (performed) Outcome.ok(Unit)
        else Outcome.err(AccessibilityError.GestureFailed("performAction or fallback gesture returned false"))
    }

    override suspend fun typeText(
        text: String, pressEnterAfter: Boolean,
    ): Outcome<Unit, AccessibilityError> = withService { s ->
        val ok = s.setTextOnFocused(text)
        if (!ok) return@withService Outcome.err(AccessibilityError.NoFocusedEditableNode)
        if (pressEnterAfter) {
            val entered = s.pressEnterOnFocused()
            if (!entered) logger.info(TAG, "pressEnterAfter requested but no focused editable accepted IME_ENTER")
        }
        Outcome.ok(Unit)
    }

    override suspend fun pressKey(key: UiKey): Outcome<Unit, AccessibilityError> = withService { s ->
        val actionId = when (key) {
            UiKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            UiKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            UiKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            UiKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            UiKey.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            UiKey.POWER_DIALOG -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            UiKey.LOCK_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            } else return@withService Outcome.err(
                AccessibilityError.SystemFailure("LOCK_SCREEN requires API 28+"),
            )
            UiKey.ENTER -> {
                // Enter is sent to the focused editable rather than a global action.
                val ok = s.pressEnterOnFocused()
                return@withService if (ok) Outcome.ok(Unit)
                else Outcome.err(AccessibilityError.NoFocusedEditableNode)
            }
        }
        if (s.globalActionAccessible(actionId)) Outcome.ok(Unit)
        else Outcome.err(AccessibilityError.GestureFailed("globalAction $actionId returned false"))
    }

    override suspend fun ensureScreenOnAndUnlocked(timeoutMs: Long): Outcome<Unit, AccessibilityError> {
        val km = context.getSystemService(KeyguardManager::class.java)
            ?: return Outcome.err(AccessibilityError.SystemFailure("KeyguardManager unavailable"))
        val pm = context.getSystemService(PowerManager::class.java)
            ?: return Outcome.err(AccessibilityError.SystemFailure("PowerManager unavailable"))

        if (!pm.isInteractive) {
            // Wake screen with a short partial wake-lock. We don't hold ACQUIRE_CAUSES_WAKEUP
            // forever — once the screen is on, any subsequent gesture keeps it awake.
            try {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "FlowDroid:UiAction",
                )
                wakeLock.acquire(timeoutMs.coerceAtMost(10_000L))
                wakeLock.release()
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) throw t
                logger.warn(TAG, "wake lock failed", t)
            }
        }

        if (!km.isKeyguardLocked) return Outcome.ok(Unit)

        // Keyguard is up — request dismissal via a transparent helper activity.
        // See `UnlockHelperActivity` for details on the activity-flag dance.
        val deferred = UnlockBridge.beginRequest()
            ?: run {
                logger.warn(TAG, "another unlock request already in flight; failing fast")
                return Outcome.err(AccessibilityError.KeyguardDismissDenied)
            }

        try {
            val intent = android.content.Intent(context, UnlockHelperActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                        android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            UnlockBridge.cancelInFlight()
            logger.warn(TAG, "could not launch UnlockHelperActivity from background", t)
            return Outcome.err(AccessibilityError.KeyguardDismissDenied)
        }

        val result = withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) { deferred.await() }
        if (result == null) {
            UnlockBridge.cancelInFlight()
            logger.warn(TAG, "keyguard dismissal timed out")
            return Outcome.err(AccessibilityError.Timeout(timeoutMs))
        }
        if (!result.success) {
            logger.warn(TAG, "keyguard dismissal denied",
                null, "reason" to result.reason)
            return Outcome.err(AccessibilityError.KeyguardDismissDenied)
        }
        // Short settle delay so the system has time to draw the just-unlocked window before we
        // start dispatching gestures.
        kotlinx.coroutines.delay(150L)
        return Outcome.ok(Unit)
    }

    override suspend fun lockScreen(): Outcome<Unit, AccessibilityError> = withService { service ->
        val ok = try {
            // GLOBAL_ACTION_LOCK_SCREEN — API 28+, always supported on minSdk 29.
            service.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
            )
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.warn(TAG, "lockScreen threw", t)
            return@withService Outcome.err(
                AccessibilityError.SystemFailure(t.message ?: "lockScreen threw"),
            )
        }
        if (ok) Outcome.ok(Unit) else Outcome.err(
            AccessibilityError.GestureFailed("performGlobalAction(LOCK_SCREEN) returned false"),
        )
    }

    // ============================================================================================
    // Internals
    // ============================================================================================

    private suspend fun dispatchSingleStroke(
        service: FlowDroidAccessibilityService,
        path: android.graphics.Path,
        startTimeMs: Long,
        durationMs: Long,
    ): Outcome<Unit, AccessibilityError> = suspendCancellableCoroutine { cont ->
        try {
            service.dispatchPath(path, startTimeMs, durationMs) { ok ->
                if (ok) cont.resume(Outcome.ok(Unit))
                else cont.resume(Outcome.err(AccessibilityError.GestureFailed("dispatch returned false")))
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            cont.resume(Outcome.err(AccessibilityError.SystemFailure(t.message ?: "dispatchPath threw")))
        }
    }

    private suspend fun dispatchDoubleTap(
        service: FlowDroidAccessibilityService, x: Float, y: Float,
    ): Outcome<Unit, AccessibilityError> = suspendCancellableCoroutine { cont ->
        try {
            service.dispatchDoubleTap(x, y) { ok ->
                if (ok) cont.resume(Outcome.ok(Unit))
                else cont.resume(Outcome.err(AccessibilityError.GestureFailed("double-tap dispatch returned false")))
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            cont.resume(Outcome.err(AccessibilityError.SystemFailure(t.message ?: "dispatchDoubleTap threw")))
        }
    }

    /**
     * Poll [rootInActiveWindow] every 200 ms for a node matching the supplied criteria, until
     * the surrounding [withTimeoutOrNull] cancels us.
     */
    private suspend fun pollForNode(
        service: FlowDroidAccessibilityService,
        mode: UiTargetMode,
        needle: String,
        textMatch: TextMatch,
        inPackage: String?,
    ): AccessibilityNodeInfo? {
        while (true) {
            val root = service.walkRootNode()
            if (root != null) {
                if (inPackage == null || root.packageName?.toString() == inPackage) {
                    val match = findInTree(root, mode, needle, textMatch)
                    if (match != null) return match
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Depth-first search for a matching node. Returns the first match. */
    private fun findInTree(
        root: AccessibilityNodeInfo,
        mode: UiTargetMode,
        needle: String,
        textMatch: TextMatch,
    ): AccessibilityNodeInfo? {
        if (matches(root, mode, needle, textMatch)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val res = findInTree(child, mode, needle, textMatch)
            if (res != null) return res
        }
        return null
    }

    private fun matches(
        node: AccessibilityNodeInfo,
        mode: UiTargetMode,
        needle: String,
        textMatch: TextMatch,
    ): Boolean = when (mode) {
        UiTargetMode.BY_TEXT -> {
            val nodeText = node.text?.toString().orEmpty()
            when (textMatch) {
                TextMatch.EXACT -> nodeText == needle
                TextMatch.CONTAINS -> nodeText.contains(needle)
                TextMatch.STARTS_WITH -> nodeText.startsWith(needle)
                TextMatch.REGEX -> try { Regex(needle).containsMatchIn(nodeText) } catch (_: Throwable) { false }
            }
        }
        UiTargetMode.BY_CONTENT_DESCRIPTION ->
            node.contentDescription?.toString() == needle
        UiTargetMode.BY_VIEW_ID ->
            node.viewIdResourceName == needle
        UiTargetMode.COORDINATES ->
            false // not used by node search
    }

    companion object {
        private const val TAG = "AccCtl"
        private const val POLL_INTERVAL_MS = 200L
    }
}
