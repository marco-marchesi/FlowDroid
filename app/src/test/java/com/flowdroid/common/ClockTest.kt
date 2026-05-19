package com.flowdroid.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ClockTest {

    @Test fun `FakeClock returns set values`() {
        val c = FakeClock(wallMillis = 1000, elapsedMillis = 50)
        assertThat(c.nowMillis()).isEqualTo(1000)
        assertThat(c.elapsedRealtime()).isEqualTo(50)
    }

    @Test fun `FakeClock advance moves both clocks`() {
        val c = FakeClock(wallMillis = 100, elapsedMillis = 100)
        c.advance(500)
        assertThat(c.nowMillis()).isEqualTo(600)
        assertThat(c.elapsedRealtime()).isEqualTo(600)
    }

    @Test fun `FakeClock setWall and setElapsed are independent`() {
        val c = FakeClock()
        c.setWall(1234)
        c.setElapsed(5678)
        assertThat(c.nowMillis()).isEqualTo(1234)
        assertThat(c.elapsedRealtime()).isEqualTo(5678)
    }

    @Test fun `nowInstant matches nowMillis`() {
        val c = FakeClock(wallMillis = 1_700_000_000_000L)
        assertThat(c.nowInstant().toEpochMilli()).isEqualTo(1_700_000_000_000L)
    }
}
