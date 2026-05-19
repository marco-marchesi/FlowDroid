package com.flowdroid.ui

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TimeFormattingTest {

    private val now = 1_700_000_000_000L

    @Test fun `future events render as 'in the future'`() {
        // Regression for F-013: previously a -3s delta returned "just now".
        assertThat(formatRelative(now + 3_000L, now)).isEqualTo("in the future")
        assertThat(formatRelative(now + 60_000L, now)).isEqualTo("in the future")
    }

    @Test fun `events within 5 seconds render as 'just now'`() {
        assertThat(formatRelative(now, now)).isEqualTo("just now")
        assertThat(formatRelative(now - 1_000L, now)).isEqualTo("just now")
        assertThat(formatRelative(now - 4_999L, now)).isEqualTo("just now")
    }

    @Test fun `events further back use formatDuration`() {
        assertThat(formatRelative(now - 30_000L, now)).isEqualTo("30s ago")
        assertThat(formatRelative(now - 2 * 60_000L, now)).isEqualTo("2m ago")
        assertThat(formatRelative(now - 65 * 60_000L, now)).contains("h")
    }

    @Test fun `formatDuration seconds`() {
        assertThat(formatDuration(0L)).isEqualTo("0s")
        assertThat(formatDuration(45_000L)).isEqualTo("45s")
    }

    @Test fun `formatDuration minutes`() {
        assertThat(formatDuration(60_000L)).isEqualTo("1m")
        assertThat(formatDuration(125_000L)).isEqualTo("2m 5s")
    }

    @Test fun `formatDuration hours`() {
        assertThat(formatDuration(3 * 60 * 60_000L)).isEqualTo("3h")
        assertThat(formatDuration(3 * 60 * 60_000L + 15 * 60_000L)).isEqualTo("3h 15m")
    }

    @Test fun `formatDuration days`() {
        assertThat(formatDuration(2L * 24 * 60 * 60_000L)).isEqualTo("2d")
        assertThat(formatDuration(2L * 24 * 60 * 60_000L + 5L * 60 * 60_000L)).isEqualTo("2d 5h")
    }
}
