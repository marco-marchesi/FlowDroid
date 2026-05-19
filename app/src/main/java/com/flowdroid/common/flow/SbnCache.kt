package com.flowdroid.common.flow

/**
 * LRU cache of "live" [android.service.notification.StatusBarNotification]s, keyed by
 * `sbn.key`. The notification listener writes here on every `onNotificationPosted`; action
 * executors (notably [Action.ClickNotificationAction]) read from it.
 *
 * Why we can't avoid this:
 *  - To fire a notification's action button we need its `Notification.Action.actionIntent`
 *    (a PendingIntent). That lives on the [android.app.Notification] object, which we get
 *    only via the SBN exposed by the listener callback. The SBN cannot be reconstructed from
 *    persisted state — once Android GCs it we lose the PendingIntent.
 *  - We therefore stash the SBN itself, NOT a parcelled copy. The cache is small (default 100).
 *  - Cache is in-memory only — survives nothing. That's fine: if the process restarts, the
 *    notification was probably already dismissed by the OS too.
 *
 * Implementations MUST be thread-safe (the listener and engine threads both access concurrently).
 */
interface SbnCache {

    /**
     * Record [sbn] keyed by [com.flowdroid.common.flow.SbnHandle.key]. Evicts the
     * least-recently-used entry if [SbnCache] is at capacity.
     */
    fun put(handle: SbnHandle)

    /**
     * Look up the [SbnHandle] for [key], or null if it's been evicted or removed.
     * Calling `get` MUST update the LRU recency.
     */
    fun get(key: String): SbnHandle?

    /** Remove the entry — used when a notification is dismissed. */
    fun remove(key: String)

    /** Number of entries currently held. Test-only. */
    val size: Int

    companion object {
        /** Default LRU capacity. Tweak in tests as needed. */
        const val DEFAULT_CAPACITY = 100
    }
}

/**
 * Thin wrapper around `StatusBarNotification`. We use a wrapper rather than the raw type
 * so [SbnCache] can live in `common/` (which is meant to stay Android-API-thin) — the
 * Android type itself is exposed via the [getActionIntent] callback only.
 *
 * @property key       SBN.key, the stable identifier for this notification.
 * @property packageName Package that posted the notification.
 * @property capturedAtMillis Wall-clock when we stashed it. Used for TTL pruning if needed.
 * @property getActionLabels Returns the labels of `Notification.Action` items, in order.
 * @property fireAction      Given an index, send the action's PendingIntent. Returns true on success.
 * @property dismiss         Dismisses the notification via `cancelNotification(sbn.key)`. Returns true on success.
 */
class SbnHandle(
    val key: String,
    val packageName: String,
    val capturedAtMillis: Long,
    val getActionLabels: () -> List<String>,
    val fireAction: (Int) -> Boolean,
    val dismiss: () -> Boolean,
)
