package com.flowdroid.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.flowdroid.MainActivity
import com.flowdroid.R
import com.flowdroid.common.Clock
import com.flowdroid.common.ServiceState
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.WatchdogOutcome
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.service.ServiceController
import com.flowdroid.common.service.ServiceRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that supervises the foreground service and notification listener.
 *
 * Why a [CoroutineWorker] rather than a plain Service heartbeat:
 *  - Survives process death — WorkManager re-schedules even if our process has been killed.
 *  - Runs even when the foreground service has been stopped by the OS, which is exactly
 *    the case we need to detect.
 *  - 15 minutes is the minimum periodic interval; we accept that as the worst-case detection
 *    latency. The foreground service's own heartbeat loop runs every 60s as a secondary check.
 *
 * The decision logic itself lives in [WatchdogLogic] so it can be unit-tested without
 * WorkManager. This class is the I/O wrapper.
 *
 * Failure handling:
 *  - We catch every Throwable inside [doWork]. The whole point of the watchdog is to *keep
 *    running*; rethrowing would let WorkManager mark us failed and back off retries.
 *  - All log writes go through Outcome — if Room is down we log to logcat and continue.
 */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val registry: ServiceRegistry,
    private val controller: ServiceController,
    private val healthRepo: HealthRepository,
    private val clock: Clock,
    private val logger: StructuredLogger,
    private val channelManager: NotificationChannelManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outcome: WatchdogOutcome = try {
            tick()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "Watchdog tick threw — swallowing to keep schedule alive", t)
            WatchdogOutcome.ERROR
        }

        recordHealthEvent(outcome)

        // Always success: we want WorkManager to keep scheduling us no matter what.
        return Result.success()
    }

    private suspend fun tick(): WatchdogOutcome {
        val ctx = applicationContext
        val snapshot = registry.snapshot()

        // 1. Detect user-revoked access (component disabled or not in enabled-listeners list).
        if (!isListenerComponentEnabled(ctx)) {
            logger.warn(
                TAG,
                "Listener component is disabled or not granted",
                fields = arrayOf(
                    "previousState" to snapshot.notificationListenerState.name,
                    "newState" to ServiceState.PERMISSION_MISSING.name,
                ),
            )
            registry.setNotificationListenerState(ServiceState.PERMISSION_MISSING)
            postPermissionMissingAlert(ctx)
            return WatchdogOutcome.PERMISSION_MISSING
        }

        // Re-read snapshot in case permission update changed it.
        val current = registry.snapshot()
        val decision = WatchdogLogic.decideWatchdogAction(
            snapshot = current,
            clock = clock,
            processStartElapsed = processStartElapsed,
        )

        when (decision) {
            WatchdogOutcome.FOREGROUND_SERVICE_RESTARTED -> {
                logger.warn(TAG, "Foreground service not running — bootstrapping",
                    fields = arrayOf("fgState" to current.foregroundServiceState.name))
                safe("controller.bootstrap") { controller.bootstrap() }
            }
            WatchdogOutcome.TOGGLED_COMPONENT -> {
                logger.warn(TAG, "Listener degraded with repeated rebinds — toggling component",
                    fields = arrayOf("rebindsLast24h" to current.rebindAttemptsLast24h))
                registry.recordRebindAttempt(clock.nowMillis())
                writeHealth(HealthEvent.Kind.LISTENER_REBIND_REQUESTED, "toggle")
                safe("controller.toggleNotificationListener") { controller.toggleNotificationListener() }
            }
            WatchdogOutcome.REBIND_REQUESTED -> {
                logger.warn(TAG, "Listener not bound — requesting rebind",
                    fields = arrayOf("listenerState" to current.notificationListenerState.name))
                registry.recordRebindAttempt(clock.nowMillis())
                writeHealth(HealthEvent.Kind.LISTENER_REBIND_REQUESTED, "requestRebind")
                safe("controller.requestNotificationListenerRebind") {
                    controller.requestNotificationListenerRebind()
                }
            }
            WatchdogOutcome.PERMISSION_MISSING -> {
                postPermissionMissingAlert(ctx)
            }
            WatchdogOutcome.ALL_OK,
            WatchdogOutcome.ERROR -> Unit
        }
        return decision
    }

    /**
     * Check that the OS still has our [FlowDroidNotificationListenerService] in its
     * enabled-listeners set. This catches the case where the user revoked access from
     * system Settings or where another app overwrote the secure setting.
     */
    private fun isListenerComponentEnabled(ctx: Context): Boolean {
        // First: component-enabled state (we may have disabled it ourselves during a toggle).
        val pm = ctx.packageManager
        val component = ComponentName(ctx, FlowDroidNotificationListenerService::class.java)
        val state = pm.getComponentEnabledSetting(component)
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false

        // Second: user-granted permission via secure settings. Avoid hidden APIs; use the
        // documented android:enabled_notification_listeners secure setting.
        return try {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            flat.split(':')
                .mapNotNull { runCatching { ComponentName.unflattenFromString(it) }.getOrNull() }
                .any { it.packageName == ctx.packageName && it.className == component.className }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            // Settings read failed — assume enabled to avoid spurious "permission lost" alerts.
            true
        }
    }

    private fun postPermissionMissingAlert(ctx: Context) {
        channelManager.ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_SETUP, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, REQ_OPEN_SETUP, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, NotificationChannelManager.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(ctx.getString(R.string.fgs_notification_text_critical))
            .setContentText(ctx.getString(R.string.fgs_notification_text_critical))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        ctx.getSystemService<NotificationManager>()?.notify(NOTIF_ID_ALERT, notification)
    }

    private suspend fun recordHealthEvent(outcome: WatchdogOutcome) {
        writeHealth(HealthEvent.Kind.WATCHDOG_TICK, outcome.name)
    }

    private suspend fun writeHealth(kind: HealthEvent.Kind, outcome: String) {
        val r = healthRepo.insert(
            HealthEvent(
                timestampMillis = clock.nowMillis(),
                kind = kind,
                message = "watchdog",
                outcome = outcome,
            )
        )
        if (r.isErr()) {
            logger.warn(
                TAG,
                "Failed to persist health event",
                fields = arrayOf("kind" to kind.name, "outcome" to outcome),
            )
        }
    }

    /** Run [block] and log+swallow any failure. The watchdog must NEVER propagate. */
    private inline fun safe(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "Watchdog operation '$label' failed", t)
        }
    }

    companion object {
        private const val TAG = "Watchdog"

        /** Unique work name — survives reinstall and lets us call UPDATE policy idempotently. */
        const val WORK_NAME = "flowdroid.watchdog.periodic"
        private const val NOTIF_ID_ALERT = 0xF1_AD
        private const val REQ_OPEN_SETUP = 0xF1_AE
        const val EXTRA_OPEN_SETUP = "com.flowdroid.extra.OPEN_SETUP"

        /** Captured at process start; used to evaluate the 2-minute boot grace window. */
        @Volatile var processStartElapsed: Long? = null

        /**
         * Schedule the watchdog at 15-min periodic intervals. Uses [ExistingPeriodicWorkPolicy.UPDATE]
         * so re-calling is idempotent — the existing schedule is preserved but the worker's
         * parameters (none here) would be refreshed.
         */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(
                15, TimeUnit.MINUTES,
            ).build()
            // KEEP rather than UPDATE: bootstrap() is called every app open / boot / package
            // replace. UPDATE would reset the periodic timer each time, which could let an
            // active user perpetually delay the next watchdog tick. KEEP is safe here because
            // the worker spec doesn't change across versions in Phase 0.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /** Cancel the scheduled watchdog. Used by [ServiceController.shutdown]. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
