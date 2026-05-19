package com.flowdroid.service

import android.app.Notification
import android.content.ComponentName
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.flowdroid.common.Clock
import com.flowdroid.common.DisconnectReason
import com.flowdroid.common.ServiceState
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.SbnCache
import com.flowdroid.common.flow.SbnHandle
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.NotificationRepository
import com.flowdroid.common.service.ServiceRegistry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single most reliability-critical component in FlowDroid.
 *
 * Strict callback budget:
 *  - [onNotificationPosted] MUST return in < 5ms. Android can flag listeners that block
 *    the binder thread; in extreme cases the OS unbinds and refuses to rebind. Every byte
 *    of work that touches Room, file IO, or computation is therefore dispatched onto an
 *    internal [CoroutineScope] backed by [Dispatchers.IO].
 *  - We measure the latency of the dispatch in elapsedRealtime() and log a WARN if
 *    a handoff ever exceeds 200ms.
 *
 * Disconnect handling:
 *  - On disconnect we set [ServiceState.DEGRADED] (not STOPPED — the OS will rebind) and
 *    immediately call [requestRebind]. PLAN.md §4.1 mandates aggressive rebind on Samsung
 *    where the listener is GC'd more frequently than vanilla Android.
 *
 * Hilt:
 *  - Annotated [@AndroidEntryPoint]. Field-injection works on Service subclasses since
 *    Hilt 2.34.
 */
@AndroidEntryPoint
class FlowDroidNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var registry: ServiceRegistry
    @Inject lateinit var notificationRepo: NotificationRepository
    @Inject lateinit var healthRepo: HealthRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var logger: StructuredLogger
    @Inject lateinit var sbnCache: SbnCache
    @Inject lateinit var flowEngine: FlowEngine

    /**
     * Scope used to offload work from the binder thread. SupervisorJob so one failing
     * insert doesn't tear down the whole scope. Named for easy filtering in logcat.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("Listener"),
    )

    override fun onCreate() {
        super.onCreate()
        logger.info(TAG, "Listener service created")
    }

    override fun onDestroy() {
        logger.info(TAG, "Listener service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val now = clock.nowMillis()
        registry.setNotificationListenerState(ServiceState.RUNNING, now)
        logger.info(
            TAG,
            "Listener connected",
            "previousState" to "?", "newState" to ServiceState.RUNNING.name,
        )
        // Persist the health event asynchronously.
        scope.launch(CoroutineName("Listener-onConnected")) {
            healthRepo.insert(
                HealthEvent(
                    timestampMillis = now,
                    kind = HealthEvent.Kind.LISTENER_CONNECTED,
                    message = "onListenerConnected",
                    outcome = null,
                )
            )
        }
    }

    override fun onListenerDisconnected() {
        // Snapshot the data we need *synchronously* before returning from the binder.
        val now = clock.nowMillis()
        val reason = DisconnectReason.UNKNOWN.name
        registry.setNotificationListenerState(ServiceState.DEGRADED)
        registry.recordListenerDisconnect(now)
        logger.warn(
            TAG,
            "Listener disconnected — requesting rebind",
            fields = arrayOf(
                "previousState" to ServiceState.RUNNING.name,
                "newState" to ServiceState.DEGRADED.name,
                "reason" to reason,
            ),
        )

        // Critical: request rebind immediately. This is a system static API.
        try {
            requestRebind(ComponentName(this, this::class.java))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.error(TAG, "requestRebind threw", t)
        }

        // Offload Room write — must not block the callback.
        scope.launch(CoroutineName("Listener-onDisconnected")) {
            healthRepo.insert(
                HealthEvent(
                    timestampMillis = now,
                    kind = HealthEvent.Kind.LISTENER_DISCONNECTED,
                    message = "onListenerDisconnected",
                    outcome = reason,
                )
            )
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // CRITICAL: every line here is on the binder thread. No Room. No file IO. No
        // synchronisation primitives that might be contended.
        val receivedElapsed = clock.elapsedRealtime()
        val receivedWall = clock.nowMillis()
        val event = try {
            toNotificationEvent(sbn, receivedWall)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.error(TAG, "Failed to extract NotificationEvent — dropping", t,
                "package" to sbn.packageName)
            return
        }

        // Cache the SBN so action executors (ClickNotificationAction) can fire its
        // PendingIntents later. The SbnCache itself is bounded LRU; this is a fast in-memory
        // put. We do this on the binder thread to capture the *live* SBN while it's valid.
        try {
            sbnCache.put(buildSbnHandle(sbn, receivedWall))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.warn(TAG, "SbnCache put failed — ClickNotificationAction may not find this SBN",
                t, "package" to event.packageName)
        }

        registry.incrementNotificationReceived()

        scope.launch(CoroutineName("Listener-insert")) {
            val handoffLatency = clock.elapsedRealtime() - receivedElapsed
            if (handoffLatency > LATENCY_BUDGET_MILLIS) {
                logger.warn(
                    TAG,
                    "Notification handoff exceeded latency budget",
                    fields = arrayOf(
                        "latencyMillis" to handoffLatency,
                        "budgetMillis" to LATENCY_BUDGET_MILLIS,
                        "package" to event.packageName,
                    ),
                )
            }
            val result = notificationRepo.insert(event)
            if (result.isErr()) {
                logger.warn(
                    TAG,
                    "Failed to persist NotificationEvent",
                    fields = arrayOf(
                        "package" to event.packageName,
                        "error" to result.errorOrNull().toString(),
                    ),
                )
            }

            // Route the event into the flow engine — it decides which (if any) flows match
            // and dispatches their actions. The engine itself never blocks this coroutine.
            try {
                flowEngine.onNotificationPosted(event)
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                logger.error(TAG, "FlowEngine.onNotificationPosted threw", t,
                    "package" to event.packageName)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Best-effort cleanup of the SbnCache. The cache is bounded LRU anyway, so missing this
        // is not a correctness issue — just frees the slot sooner.
        try {
            sbnCache.remove(sbn.key)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.warn(TAG, "SbnCache remove failed", t, "key" to sbn.key)
        }
        super.onNotificationRemoved(sbn)
    }

    /**
     * Build an [SbnHandle] over the supplied [sbn]. The closures capture the SBN reference; the
     * listener (this service) must remain alive for them to work — which is guaranteed because
     * the cache is in-process and bounded LRU.
     *
     * `fireAction(i)` dispatches the i-th `Notification.Action`'s PendingIntent. Returns false if
     * the index is out of range, the action has no intent, or the send fails.
     *
     * `dismiss()` calls [cancelNotification] on the listener which is the documented way to
     * dismiss a notification from a NotificationListenerService.
     */
    private fun buildSbnHandle(sbn: StatusBarNotification, capturedAtMillis: Long): SbnHandle {
        val n = sbn.notification
        return SbnHandle(
            key = sbn.key,
            packageName = sbn.packageName,
            capturedAtMillis = capturedAtMillis,
            getActionLabels = {
                n?.actions?.mapNotNull { it.title?.toString() } ?: emptyList()
            },
            fireAction = fireAction@{ index ->
                val actions = n?.actions ?: return@fireAction false
                if (index !in actions.indices) return@fireAction false
                val action = actions[index]
                val pi = action.actionIntent ?: return@fireAction false
                try {
                    pi.send()
                    true
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError) throw t
                    logger.warn(TAG, "PendingIntent.send failed", t,
                        "actionLabel" to (action.title?.toString() ?: "<null>"))
                    false
                }
            },
            dismiss = dismiss@{
                try {
                    cancelNotification(sbn.key)
                    true
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError) throw t
                    logger.warn(TAG, "cancelNotification failed", t, "key" to sbn.key)
                    false
                }
            },
        )
    }

    /**
     * Convert a [StatusBarNotification] to our domain event. Pure-ish — only reads from the
     * SBN and uses the injected clock. Throws nothing the caller can't catch.
     */
    private fun toNotificationEvent(sbn: StatusBarNotification, receivedWall: Long): NotificationEvent {
        val n = sbn.notification
        val extras = n?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val ticker = n?.tickerText?.toString()
        val actionLabels = n?.actions?.mapNotNull { it.title?.toString() } ?: emptyList()
        val rawExtrasJson = extras?.let { dumpExtrasJson(it) }

        return NotificationEvent(
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            postTimeMillis = receivedWall,
            notificationPostTimeMillis = sbn.postTime,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            tickerText = ticker,
            channelId = n?.channelId,
            groupKey = sbn.groupKey,
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            isGroupSummary = ((n?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY) != 0,
            importance = null, // listener does not expose channel-importance; future PermChecker
            notificationId = sbn.id,
            actionLabels = actionLabels,
            rawExtrasJson = rawExtrasJson,
        )
    }

    /**
     * Best-effort JSON-ish dump of the extras bundle. We never want to serialise binary
     * payloads (Bitmaps, Parcelables) since they can be tens of MB. Anything we can't safely
     * stringify is recorded as `"<binary>"` to preserve the key name without the payload.
     *
     * Output is *not* guaranteed to be valid JSON — it's a debugging aid. The Phase 1 magic-text
     * engine will read from typed fields, not this blob.
     */
    private fun dumpExtrasJson(extras: android.os.Bundle): String {
        val sb = StringBuilder("{")
        var first = true
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            val value = try {
                extras.get(key)
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) throw t
                "<unreadable>"
            }
            val safe: String = when (value) {
                null -> "null"
                is CharSequence -> "\"" + escape(value.toString()) + "\""
                is Number, is Boolean -> value.toString()
                is Parcelable -> "\"<binary>\""
                is ByteArray -> "\"<binary>\""
                is android.graphics.Bitmap -> "\"<binary>\""
                else -> "\"" + escape(value.toString().take(MAX_VALUE_LEN)) + "\""
            }
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(key)).append("\":").append(safe)
        }
        sb.append('}')
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").take(MAX_VALUE_LEN)

    companion object {
        private const val TAG = "Listener"
        /** Single-line cap for stringified extras values to keep the dump bounded. */
        private const val MAX_VALUE_LEN = 256
        /** Hard latency budget for binder→IO handoff (in millis). Exceeding it triggers a WARN. */
        const val LATENCY_BUDGET_MILLIS = 200L
    }
}
