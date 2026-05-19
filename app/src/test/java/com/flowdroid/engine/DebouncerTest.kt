package com.flowdroid.engine

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DebouncerTest {

    private val debouncer = Debouncer()

    @Test fun `first fire is allowed`() {
        assertThat(debouncer.tryFire("f1", 1000L, windowMillis = 1500L)).isTrue()
    }

    @Test fun `second fire within window is denied`() {
        debouncer.tryFire("f1", 1000L, 1500L)
        assertThat(debouncer.tryFire("f1", 1500L, 1500L)).isFalse()
        assertThat(debouncer.tryFire("f1", 2499L, 1500L)).isFalse()
    }

    @Test fun `fire after window is allowed`() {
        debouncer.tryFire("f1", 1000L, 1500L)
        assertThat(debouncer.tryFire("f1", 2500L, 1500L)).isTrue()
    }

    @Test fun `subsequent allowed fire resets the window`() {
        debouncer.tryFire("f1", 1000L, 1500L)
        assertThat(debouncer.tryFire("f1", 2500L, 1500L)).isTrue() // resets to 2500
        // 3000 < 2500 + 1500 → denied
        assertThat(debouncer.tryFire("f1", 3000L, 1500L)).isFalse()
        // 4001 >= 2500 + 1500 → allowed
        assertThat(debouncer.tryFire("f1", 4001L, 1500L)).isTrue()
    }

    @Test fun `per-flow isolation`() {
        assertThat(debouncer.tryFire("f1", 1000L, 1500L)).isTrue()
        // Different flow, same time — should be allowed independently.
        assertThat(debouncer.tryFire("f2", 1000L, 1500L)).isTrue()
        // f1 still denied within its window
        assertThat(debouncer.tryFire("f1", 1100L, 1500L)).isFalse()
        // f2 still denied within its window
        assertThat(debouncer.tryFire("f2", 1100L, 1500L)).isFalse()
    }

    @Test fun `zero window means always allowed`() {
        assertThat(debouncer.tryFire("f1", 1000L, 0L)).isTrue()
        assertThat(debouncer.tryFire("f1", 1000L, 0L)).isTrue()
        assertThat(debouncer.tryFire("f1", 1001L, 0L)).isTrue()
    }

    @Test fun `reset clears state`() {
        debouncer.tryFire("f1", 1000L, 1500L)
        debouncer.reset()
        assertThat(debouncer.tryFire("f1", 1100L, 1500L)).isTrue()
    }
}
