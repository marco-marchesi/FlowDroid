package com.flowdroid.engine

import com.flowdroid.common.flow.SbnHandle
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SbnCacheImplTest {

    private val cache = SbnCacheImpl()

    private fun handle(key: String) = SbnHandle(
        key = key,
        packageName = "pkg",
        capturedAtMillis = 0L,
        getActionLabels = { emptyList() },
        fireAction = { false },
        dismiss = { false },
    )

    @Test fun `put and get`() {
        val h = handle("k1")
        cache.put(h)
        assertThat(cache.get("k1")).isSameInstanceAs(h)
        assertThat(cache.size).isEqualTo(1)
    }

    @Test fun `get returns null for unknown key`() {
        assertThat(cache.get("missing")).isNull()
    }

    @Test fun `remove evicts entry`() {
        cache.put(handle("k1"))
        cache.remove("k1")
        assertThat(cache.get("k1")).isNull()
        assertThat(cache.size).isEqualTo(0)
    }

    @Test fun `LRU eviction when over capacity`() {
        // Fill above capacity. We can't easily change capacity post-construction; instead we
        // verify the bound by inserting capacity+1 and observing eviction of the oldest.
        val capacity = com.flowdroid.common.flow.SbnCache.DEFAULT_CAPACITY
        for (i in 0 until capacity) {
            cache.put(handle("k$i"))
        }
        assertThat(cache.size).isEqualTo(capacity)

        // Insert one more → eldest (k0) should evict.
        cache.put(handle("k$capacity"))
        assertThat(cache.size).isEqualTo(capacity)
        assertThat(cache.get("k0")).isNull()
        assertThat(cache.get("k$capacity")).isNotNull()
    }

    @Test fun `get updates recency (LRU)`() {
        val capacity = com.flowdroid.common.flow.SbnCache.DEFAULT_CAPACITY
        for (i in 0 until capacity) {
            cache.put(handle("k$i"))
        }
        // Touch k0 → it should now be the most recently used. Inserting one more should
        // evict k1, not k0.
        cache.get("k0")
        cache.put(handle("knew"))
        assertThat(cache.get("k0")).isNotNull()
        assertThat(cache.get("k1")).isNull()
    }

    @Test fun `thread safety smoke test`() {
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val errors = mutableListOf<Throwable>()
        repeat(4) { t ->
            pool.submit {
                try {
                    repeat(500) { i ->
                        val key = "t${t}_k${i % 50}"
                        cache.put(handle(key))
                        cache.get(key)
                        if (i % 10 == 0) cache.remove(key)
                    }
                } catch (e: Throwable) {
                    synchronized(errors) { errors += e }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(errors).isEmpty()
    }
}
