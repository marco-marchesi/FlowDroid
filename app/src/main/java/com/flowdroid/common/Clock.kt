package com.flowdroid.common

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction over current time. Injected so tests can fake it deterministically.
 *
 * Rules:
 *  - Production code MUST use this, never call `System.currentTimeMillis()` or `Instant.now()` directly.
 *  - Use [nowMillis] for wall-clock timestamps (notifications, log entries).
 *  - Use [elapsedRealtime] for measuring durations across sleep — important for
 *    watchdog timing because Doze can wall-clock-jump us.
 */
interface Clock {
    /** Wall clock, milliseconds since epoch. Maps to [System.currentTimeMillis]. */
    fun nowMillis(): Long

    /** Wall-clock [Instant]. */
    fun nowInstant(): Instant = Instant.ofEpochMilli(nowMillis())

    /**
     * Monotonic millis since boot, *including* deep-sleep time. Maps to
     * [android.os.SystemClock.elapsedRealtime].
     *
     * Use this for any duration measurement that must survive Doze.
     */
    fun elapsedRealtime(): Long
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

/** Test-only clock. Caller controls both wall and elapsed time. */
class FakeClock(
    private var wallMillis: Long = 0L,
    private var elapsedMillis: Long = 0L,
) : Clock {
    override fun nowMillis(): Long = wallMillis
    override fun elapsedRealtime(): Long = elapsedMillis

    fun setWall(millis: Long) { wallMillis = millis }
    fun setElapsed(millis: Long) { elapsedMillis = millis }
    fun advance(millis: Long) { wallMillis += millis; elapsedMillis += millis }
}
