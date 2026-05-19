package com.flowdroid.platform.schedule

import com.flowdroid.common.flow.DayOfWeek
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-function table-driven tests for [nextFireMillis]. The function is internal to the
 * `com.flowdroid.platform.schedule` package, which is why this test lives in the same package.
 *
 * All cases construct `now` and the expected fire instant via [Calendar] so the test code is
 * self-evident about the wall-clock intent — comparing raw millis would be unreadable.
 */
class NextFireMillisTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun ms(tz: TimeZone, y: Int, m: Int, d: Int, h: Int, min: Int): Long {
        val cal = Calendar.getInstance(tz)
        cal.clear()
        cal.set(y, m - 1, d, h, min, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dayOfWeek(tz: TimeZone, millis: Long): DayOfWeek {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = millis
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> DayOfWeek.MON
            Calendar.TUESDAY -> DayOfWeek.TUE
            Calendar.WEDNESDAY -> DayOfWeek.WED
            Calendar.THURSDAY -> DayOfWeek.THU
            Calendar.FRIDAY -> DayOfWeek.FRI
            Calendar.SATURDAY -> DayOfWeek.SAT
            else -> DayOfWeek.SUN
        }
    }

    @Test fun `today HH-MM later today fires today`() {
        // 2026-05-19 (Tuesday) 10:00 UTC, ask for 14:30 same day.
        val now = ms(utc, 2026, 5, 19, 10, 0)
        val expected = ms(utc, 2026, 5, 19, 14, 30)
        val next = nextFireMillis(now, hour = 14, minute = 30, daysOfWeek = emptySet(), tz = utc)
        assertThat(next).isEqualTo(expected)
    }

    @Test fun `today HH-MM already past — fires tomorrow`() {
        // 2026-05-19 18:00 UTC, ask for 09:00.
        val now = ms(utc, 2026, 5, 19, 18, 0)
        val expected = ms(utc, 2026, 5, 20, 9, 0)
        val next = nextFireMillis(now, 9, 0, emptySet(), utc)
        assertThat(next).isEqualTo(expected)
    }

    @Test fun `same minute counts as past — fires tomorrow`() {
        // The function fires only on STRICTLY future instants — re-arming at the exact fire moment
        // must roll to the next occurrence; otherwise we'd loop on the same instant.
        val now = ms(utc, 2026, 5, 19, 10, 0)
        val expected = ms(utc, 2026, 5, 20, 10, 0)
        val next = nextFireMillis(now, 10, 0, emptySet(), utc)
        assertThat(next).isEqualTo(expected)
    }

    @Test fun `empty daysOfWeek means every day`() {
        // Saturday → Sunday rollover should not skip a day.
        val now = ms(utc, 2026, 5, 23, 23, 0) // Saturday 23:00 UTC
        val expected = ms(utc, 2026, 5, 24, 8, 0) // Sunday 08:00
        val next = nextFireMillis(now, 8, 0, emptySet(), utc)
        assertThat(next).isEqualTo(expected)
        assertThat(dayOfWeek(utc, next)).isEqualTo(DayOfWeek.SUN)
    }

    @Test fun `weekday filter skips disallowed days`() {
        // Friday 19:00. Only MON allowed. Should jump to next Monday 08:00.
        val now = ms(utc, 2026, 5, 22, 19, 0) // 2026-05-22 is a Friday
        val next = nextFireMillis(now, 8, 0, setOf(DayOfWeek.MON), utc)
        val expected = ms(utc, 2026, 5, 25, 8, 0) // Monday
        assertThat(next).isEqualTo(expected)
        assertThat(dayOfWeek(utc, next)).isEqualTo(DayOfWeek.MON)
    }

    @Test fun `today is allowed weekday but time passed — picks next allowed weekday`() {
        // Monday 10:00, MON-only at 09:00. Today's 09:00 is past → next Monday.
        val now = ms(utc, 2026, 5, 18, 10, 0) // 2026-05-18 is Monday
        val next = nextFireMillis(now, 9, 0, setOf(DayOfWeek.MON), utc)
        val expected = ms(utc, 2026, 5, 25, 9, 0)
        assertThat(next).isEqualTo(expected)
    }

    @Test fun `weekday filter — MON THU set picks next Thu`() {
        val now = ms(utc, 2026, 5, 19, 10, 0) // Tuesday
        val next = nextFireMillis(now, 7, 30, setOf(DayOfWeek.MON, DayOfWeek.THU), utc)
        val expected = ms(utc, 2026, 5, 21, 7, 30) // Thursday
        assertThat(next).isEqualTo(expected)
        assertThat(dayOfWeek(utc, next)).isEqualTo(DayOfWeek.THU)
    }

    @Test fun `DST spring-forward — 02-30 on transition day still produces a strictly-future instant`() {
        // Europe/Rome 2026 DST transition: 2026-03-29, 02:00 → 03:00 local. Asking for 02:30
        // local on that day yields an instant the JDK Calendar resolves to either 03:30
        // (skipping forward) or pinned to 03:00. We assert only the must-hold invariants:
        // strictly > now, and within 26 hours of now (so we didn't skip a whole day).
        val rome = TimeZone.getTimeZone("Europe/Rome")
        val now = ms(rome, 2026, 3, 29, 0, 0) // 00:00 local that morning
        val next = nextFireMillis(now, 2, 30, emptySet(), rome)
        assertThat(next).isGreaterThan(now)
        assertThat(next - now).isLessThan(26L * 3600_000L)
    }

    @Test fun `DST fall-back — 02-30 resolves to a strictly-future instant`() {
        // Europe/Rome fall-back: 2026-10-25, 03:00 → 02:00 local. 02:30 occurs twice;
        // Calendar picks the first by default. We assert future + sane delta only.
        val rome = TimeZone.getTimeZone("Europe/Rome")
        val now = ms(rome, 2026, 10, 25, 1, 0)
        val next = nextFireMillis(now, 2, 30, emptySet(), rome)
        assertThat(next).isGreaterThan(now)
        // < 24h: even with a falling-back day the gap stays reasonable.
        assertThat(next - now).isLessThan(25L * 3600_000L)
    }

    @Test fun `weekday filter with all 7 days behaves like empty set`() {
        val now = ms(utc, 2026, 5, 19, 18, 0)
        val viaAll = nextFireMillis(now, 9, 0, DayOfWeek.entries.toSet(), utc)
        val viaEmpty = nextFireMillis(now, 9, 0, emptySet(), utc)
        assertThat(viaAll).isEqualTo(viaEmpty)
    }

    @Test fun `out-of-range hour throws`() {
        try {
            nextFireMillis(0L, 24, 0, emptySet(), utc)
            error("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("hour")
        }
    }

    @Test fun `out-of-range minute throws`() {
        try {
            nextFireMillis(0L, 0, 60, emptySet(), utc)
            error("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("minute")
        }
    }
}
