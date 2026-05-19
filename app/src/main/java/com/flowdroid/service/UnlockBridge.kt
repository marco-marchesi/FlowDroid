package com.flowdroid.service

import kotlinx.coroutines.CompletableDeferred

/**
 * Bridge between [UnlockHelperActivity] (which lives on the activity-launch path) and the
 * [com.flowdroid.engine.accessibility.AccessibilityControllerImpl] coroutine that awaits the
 * dismissal result.
 *
 * Only one outstanding request is allowed at a time — the controller serialises calls into
 * `ensureScreenOnAndUnlocked()` because gesture actions run sequentially anyway. If a second
 * request comes in while one is in flight, the second is rejected with `false` so it can fail
 * fast rather than wait indefinitely for someone else's keyguard.
 */
object UnlockBridge {

    @Volatile
    private var inFlight: CompletableDeferred<Result>? = null
    private val lock = Any()

    /**
     * Begin a new dismissal request. Returns the [CompletableDeferred] the caller awaits.
     * Returns null if a request is already in flight (caller must fail).
     */
    fun beginRequest(): CompletableDeferred<Result>? = synchronized(lock) {
        if (inFlight != null) return null
        val d = CompletableDeferred<Result>()
        inFlight = d
        d
    }

    /** Called by [UnlockHelperActivity] when the dismiss callback fires. */
    fun complete(success: Boolean, reason: String) = synchronized(lock) {
        inFlight?.complete(Result(success, reason))
        inFlight = null
    }

    /** Abandon an in-flight request (e.g. on timeout) so a new one can begin. */
    fun cancelInFlight() = synchronized(lock) {
        inFlight?.complete(Result(false, "timeout/cancelled"))
        inFlight = null
    }

    data class Result(val success: Boolean, val reason: String)
}
