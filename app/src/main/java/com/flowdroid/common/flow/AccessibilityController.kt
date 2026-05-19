package com.flowdroid.common.flow

import com.flowdroid.common.Outcome

/**
 * Façade that the engine uses to drive the [com.flowdroid.service.FlowDroidAccessibilityService].
 *
 * Why a façade rather than letting executors talk to the service directly:
 *  - The service comes and goes (user can disable it in Settings). The controller exposes
 *    [isReady] so executors can fail fast with a typed error rather than NPE.
 *  - The service runs on the binder thread. The controller marshals every call onto a single
 *    dedicated dispatcher so we don't accidentally call back into a destroyed service from
 *    arbitrary coroutines.
 *  - Tests can mock this interface trivially; without it they'd have to fake an `AccessibilityService`.
 *
 * Implementations MUST be thread-safe and MUST NOT block the caller — every operation is
 * asynchronous (suspend) and returns an [Outcome].
 */
interface AccessibilityController {

    /** Whether the accessibility service is currently bound and ready to dispatch gestures. */
    fun isReady(): Boolean

    /**
     * Tap, double-tap, or long-press at absolute screen coordinates.
     *
     * @param holdMs    Long-press hold duration. 0 for single, ~50 for double's individual taps.
     */
    suspend fun tap(x: Float, y: Float, mode: ClickMode, holdMs: Long): Outcome<Unit, AccessibilityError>

    /** Swipe between two screen points over [durationMs]. */
    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Outcome<Unit, AccessibilityError>

    /**
     * Find a node by the supplied matcher within [timeoutMs]; click it once it appears.
     *
     * @param mode    What to match against ([UiTargetMode.BY_TEXT] / [UiTargetMode.BY_CONTENT_DESCRIPTION] / [UiTargetMode.BY_VIEW_ID]).
     * @param needle  The text/desc/id to look for.
     * @param textMatch How [needle] is compared when [mode] is BY_TEXT.
     * @param inPackage Optional package filter — only consider nodes within this package's window.
     * @param clickMode Whether to single/double/long-click the found node.
     */
    suspend fun clickByNode(
        mode: UiTargetMode,
        needle: String,
        textMatch: TextMatch,
        inPackage: String?,
        clickMode: ClickMode,
        longPressDurationMs: Long,
        timeoutMs: Long,
    ): Outcome<Unit, AccessibilityError>

    /**
     * Type [text] into the currently-focused editable node. If [pressEnterAfter], also send
     * IME_ACTION_DONE.
     */
    suspend fun typeText(text: String, pressEnterAfter: Boolean): Outcome<Unit, AccessibilityError>

    /** Press a global navigation key. */
    suspend fun pressKey(key: UiKey): Outcome<Unit, AccessibilityError>

    /**
     * Wake the screen and dismiss the keyguard if currently locked. No-op if already unlocked.
     * Returns Ok if the device is unlocked (or was already unlocked) when this returns.
     *
     * Used by gesture actions before they dispatch — the accessibility service cannot see UI
     * that isn't drawn (lock screen, screen off).
     */
    suspend fun ensureScreenOnAndUnlocked(timeoutMs: Long = 5000L): Outcome<Unit, AccessibilityError>
}

sealed interface AccessibilityError {
    data object ServiceNotBound : AccessibilityError
    data class GestureFailed(val reason: String) : AccessibilityError
    data class NodeNotFound(val mode: UiTargetMode, val needle: String) : AccessibilityError
    data object NoFocusedEditableNode : AccessibilityError
    data class SystemFailure(val message: String) : AccessibilityError
    data class Timeout(val afterMillis: Long) : AccessibilityError
    data object KeyguardDismissDenied : AccessibilityError
}
