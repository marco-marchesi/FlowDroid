package com.flowdroid.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OutcomeTest {

    @Test fun `Ok carries value`() {
        val o: Outcome<Int, String> = Outcome.ok(42)
        assertThat(o.isOk()).isTrue()
        assertThat(o.isErr()).isFalse()
        assertThat(o.valueOrNull()).isEqualTo(42)
        assertThat(o.errorOrNull()).isNull()
    }

    @Test fun `Err carries error and cause`() {
        val cause = RuntimeException("boom")
        val o: Outcome<Int, String> = Outcome.err("nope", cause)
        assertThat(o.isErr()).isTrue()
        assertThat(o.valueOrNull()).isNull()
        assertThat(o.errorOrNull()).isEqualTo("nope")
        assertThat((o as Outcome.Err).cause).isSameInstanceAs(cause)
    }

    @Test fun `map transforms Ok only`() {
        val ok: Outcome<Int, String> = Outcome.ok(2)
        assertThat(ok.map { it * 10 }.valueOrNull()).isEqualTo(20)
        val err: Outcome<Int, String> = Outcome.err("e")
        assertThat(err.map { it * 10 }.errorOrNull()).isEqualTo("e")
    }

    @Test fun `mapErr transforms Err only`() {
        val ok: Outcome<Int, String> = Outcome.ok(2)
        assertThat(ok.mapErr { it.uppercase() }.valueOrNull()).isEqualTo(2)
        val err: Outcome<Int, String> = Outcome.err("e")
        assertThat(err.mapErr { it.uppercase() }.errorOrNull()).isEqualTo("E")
    }

    @Test fun `flatMap chains Ok`() {
        val r = Outcome.ok(2).flatMap<Int, String, Int> { Outcome.ok(it + 1) }
        assertThat(r.valueOrNull()).isEqualTo(3)
    }

    @Test fun `flatMap short-circuits Err`() {
        val r = (Outcome.err<String>("first") as Outcome<Int, String>)
            .flatMap { Outcome.ok(it + 1) }
        assertThat(r.errorOrNull()).isEqualTo("first")
    }

    @Test fun `onOk only fires for Ok`() {
        var hits = 0
        Outcome.ok(1).onOk { hits++ }
        (Outcome.err("e") as Outcome<Int, String>).onOk { hits++ }
        assertThat(hits).isEqualTo(1)
    }

    @Test fun `onErr only fires for Err`() {
        var hits = 0
        Outcome.ok(1).onErr { _, _ -> hits++ }
        Outcome.err("e").onErr { _, _ -> hits++ }
        assertThat(hits).isEqualTo(1)
    }

    @Test fun `getOrThrow returns or throws`() {
        assertThat(Outcome.ok(5).getOrThrow()).isEqualTo(5)
        assertThrows(IllegalStateException::class.java) {
            (Outcome.err("e") as Outcome<Int, String>).getOrThrow()
        }
    }

    @Test fun `outcomeCatching wraps thrown exception`() {
        val r = outcomeCatching({ "io: ${it.message}" }) {
            throw java.io.IOException("disk full")
        }
        assertThat(r.errorOrNull()).isEqualTo("io: disk full")
        assertThat((r as Outcome.Err).cause).isInstanceOf(java.io.IOException::class.java)
    }

    @Test fun `outcomeCatching rethrows cancellation and OOM`() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            outcomeCatching<Int, String>({ "" }) {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertThrows(OutOfMemoryError::class.java) {
            outcomeCatching<Int, String>({ "" }) { throw OutOfMemoryError() }
        }
    }
}
