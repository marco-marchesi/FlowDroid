package com.flowdroid.engine

import com.flowdroid.common.flow.SbnCache
import com.flowdroid.common.flow.SbnHandle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [SbnCache] implementation: bounded LRU keyed on `SBN.key`.
 *
 * Why a [LinkedHashMap] in access-order mode:
 *  - The cache must update the recency of an entry on `get()` so we don't evict the SBN we are
 *    actively trying to fire an action on. `LinkedHashMap(accessOrder=true)` does this for free.
 *  - We override `removeEldestEntry` to enforce the [SbnCache.DEFAULT_CAPACITY] bound. This is
 *    O(1) and avoids the cost of a separate eviction step.
 *
 * Why `synchronized(lock)` rather than `Collections.synchronizedMap`:
 *  - `synchronizedMap` doesn't synchronise compound operations (`get`+`remove`, the `accessOrder`
 *    mutation triggered by `get`). We need a single lock covering the whole access.
 *  - The listener thread and the engine action-executor coroutine both touch this map; the lock
 *    is held for microseconds so contention is a non-issue.
 *
 * Lifetime: in-memory only. If the process dies, all handles die with it — but so does the
 * `PendingIntent` the handles wrap, so persisting them is meaningless.
 */
@Singleton
class SbnCacheImpl @Inject constructor() : SbnCache {

    private val capacity: Int = SbnCache.DEFAULT_CAPACITY
    private val lock = Any()

    // `accessOrder = true` makes get() promote the entry; `removeEldestEntry` enforces capacity.
    private val map: LinkedHashMap<String, SbnHandle> =
        object : LinkedHashMap<String, SbnHandle>(capacity, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, SbnHandle>?): Boolean {
                return size > capacity
            }
        }

    override fun put(handle: SbnHandle) {
        synchronized(lock) {
            map[handle.key] = handle
        }
    }

    override fun get(key: String): SbnHandle? = synchronized(lock) {
        map[key]
    }

    override fun remove(key: String) {
        synchronized(lock) {
            map.remove(key)
        }
    }

    override val size: Int
        get() = synchronized(lock) { map.size }
}
