package com.flowdroid.engine

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-flow debounce gate.
 *
 * Why this exists:
 *  - Notifications often arrive in bursts (an event triggers 2-3 redundant posts within
 *    a second from the OS). Without a debounce, we would fire the same flow multiple times,
 *    triggering the same action multiple times.
 *  - Debouncing is per-flow because two unrelated flows may legitimately match the same event
 *    or fire concurrently. We key on `flowId`, not on event content.
 *
 * Semantics:
 *  - First call for a flow → allowed.
 *  - Subsequent calls within `windowMillis` of the recorded firing → denied.
 *  - Outside the window → allowed AND the new timestamp replaces the old one.
 *
 * Thread-safety: backed by [ConcurrentHashMap]; race between two evaluators for the same
 * flow may result in both seeing "allowed". That is acceptable for Phase 1 — true serialisation
 * across threads would require an actor/mutex per flow, which is over-engineered for the goal
 * of "swallow OS-level duplicates within 1.5 s".
 */
@Singleton
class Debouncer @Inject constructor() {

    private val lastFireMillis = ConcurrentHashMap<String, Long>()

    /**
     * Attempt to fire flow [flowId] at [nowMillis]. Returns true if allowed; updates the
     * recorded last-fire timestamp atomically when allowed.
     *
     * @param windowMillis Debounce window. 0 means "no debounce".
     */
    fun tryFire(flowId: String, nowMillis: Long, windowMillis: Long): Boolean {
        if (windowMillis <= 0L) {
            lastFireMillis[flowId] = nowMillis
            return true
        }
        // compute+merge atomically so concurrent callers can't both pass.
        var allowed = false
        lastFireMillis.compute(flowId) { _, prev ->
            if (prev == null || nowMillis - prev >= windowMillis) {
                allowed = true
                nowMillis
            } else {
                prev
            }
        }
        return allowed
    }

    /** Test-only: reset all recorded state. */
    fun reset() {
        lastFireMillis.clear()
    }
}
