package com.flowdroid.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.flowdroid.MainActivity
import com.flowdroid.R
import com.flowdroid.common.Clock
import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.HealthSnapshot.OverallStatus
import com.flowdroid.common.ServiceState
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.service.ServiceRegistry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The orchestrator. Keeps the FlowDroid process alive so the [NotificationListenerService]
 * stays bound. Does not itself receive notifications.
 *
 * Lifecycle:
 *  - On [onCreate] we ensure channels, post the FGS notification, transition the
 *    [ServiceRegistry] to STARTING then RUNNING, start the heartbeat loop, and start the
 *    adaptive-notification observer.
 *  - [onStartCommand] returns [START_STICKY] so the OS re-creates us after a kill.
 *  - On [onDestroy] we transition the registry to STOPPED and cancel all coroutines.
 *
 * Foreground service type:
 *  - API 34+: we must declare a SPECIAL_USE type and the OS enforces it (also matched in
 *    AndroidManifest.xml). We OR with DATA_SYNC for forward compatibility on devices that
 *    silently accept both.
 *  - API 29-33: we use DATA_SYNC. SPECIAL_USE was not yet introduced in this range.
 *
 * Adaptive notification:
 *  - We observe [ServiceRegistry.observeSnapshot] and re-render the FGS notification whenever
 *    the overall status changes. On RED state we additionally post a HIGH-importance alert
 *    on [NotificationChannelManager.CHANNEL_ALERTS] so the user gets a strong signal.
 */
@AndroidEntryPoint
class FlowDroidForegroundService : Service() {

    @Inject lateinit var registry: ServiceRegistry
    @Inject lateinit var healthRepo: HealthRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var logger: StructuredLogger
    @Inject lateinit var channelManager: NotificationChannelManager

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("FgService"),
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Capture process start as early as possible for the watchdog's boot-grace check.
        if (WatchdogWorker.processStartElapsed == null) {
            WatchdogWorker.processStartElapsed = clock.elapsedRealtime()
        }

        channelManager.ensureChannels(this)

        registry.setForegroundServiceState(ServiceState.STARTING)
        logger.info(
            TAG, "FGS onCreate",
            "previousState" to ServiceState.UNKNOWN.name,
            "newState" to ServiceState.STARTING.name,
        )

        // Initial notification (overall status will normally be UNKNOWN until first listener tick).
        val initial = registry.snapshot()
        startFgsWithNotification(buildFgsNotification(initial.overallStatus))

        registry.setForegroundServiceState(ServiceState.RUNNING)
        logger.info(
            TAG, "FGS RUNNING",
            "previousState" to ServiceState.STARTING.name,
            "newState" to ServiceState.RUNNING.name,
        )

        scope.launch(CoroutineName("FgService-onStarted")) {
            healthRepo.insert(
                HealthEvent(
                    timestampMillis = clock.nowMillis(),
                    kind = HealthEvent.Kind.FOREGROUND_SERVICE_STARTED,
                    message = "onCreate",
                    outcome = null,
                )
            )
        }

        startHeartbeat()
        startAdaptiveNotificationObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the OS kills us, re-create *without* the original intent.
        // The orchestrator never relies on the intent payload — bootstrap is idempotent.
        return START_STICKY
    }

    override fun onDestroy() {
        logger.info(
            TAG, "FGS onDestroy",
            "previousState" to ServiceState.RUNNING.name,
            "newState" to ServiceState.STOPPED.name,
        )
        registry.setForegroundServiceState(ServiceState.STOPPED)
        scope.launch(CoroutineName("FgService-onStopped")) {
            healthRepo.insert(
                HealthEvent(
                    timestampMillis = clock.nowMillis(),
                    kind = HealthEvent.Kind.FOREGROUND_SERVICE_STOPPED,
                    message = "onDestroy",
                    outcome = null,
                )
            )
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Start the FGS with the supplied notification. Uses the SPECIAL_USE type on API 34+
     * (OR'd with DATA_SYNC for safety) and DATA_SYNC on API 29-33. Type ORs allow the OS to
     * pick the most appropriate constraint set.
     */
    private fun startFgsWithNotification(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE /* 34 */) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q /* 29 */) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            // ForegroundServiceStartNotAllowedException (API 31+) and ForegroundServiceTypeException
            // (API 34+) are subclasses of IllegalStateException; log and let onStartCommand return
            // — the watchdog will retry later.
            logger.error(TAG, "startForeground threw", t)
        }
    }

    /**
     * Heartbeat loop. Every 60s we insert a [HealthEvent.Kind.WATCHDOG_TICK] so the in-app
     * "is anything alive" check has a recent timestamp even when the [WatchdogWorker] (which
     * runs only every 15 minutes minimum) hasn't fired yet.
     */
    private fun startHeartbeat() {
        scope.launch(CoroutineName("FgService-heartbeat")) {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                val r = healthRepo.insert(
                    HealthEvent(
                        timestampMillis = clock.nowMillis(),
                        kind = HealthEvent.Kind.WATCHDOG_TICK,
                        message = "fgs-heartbeat",
                        outcome = "HEARTBEAT",
                    )
                )
                if (r.isErr()) {
                    logger.warn(TAG, "Heartbeat insert failed",
                        fields = arrayOf("error" to r.errorOrNull().toString()))
                }
            }
        }
    }

    /**
     * Re-render the FGS notification whenever overall status changes. Distinct-until-changed
     * so we don't churn the notification on every minor state edit.
     *
     * Two-notification model (see REVIEW_FINDINGS F-001):
     *  - The FGS notification ALWAYS uses [NotificationChannelManager.CHANNEL_FGS] regardless
     *    of status. Only the title/text/priority vary. This preserves the FGS notification's
     *    binding to its original channel — Android does not handle channel changes on a
     *    foreground notification gracefully.
     *  - When status transitions to RED, we additionally post a SEPARATE notification on
     *    [NotificationChannelManager.CHANNEL_ALERTS] with a different id. That separate alert
     *    is the high-importance buzz; the FGS notification stays low-key.
     *  - When status leaves RED, the separate alert is cancelled.
     */
    private fun startAdaptiveNotificationObserver() {
        scope.launch(CoroutineName("FgService-adaptive")) {
            registry.observeSnapshot()
                .map { it.overallStatus }
                .distinctUntilChanged()
                .collect { status ->
                    logger.info(
                        TAG, "Overall status changed — re-rendering FGS notification",
                        "newStatus" to status.name,
                    )
                    val nm = androidx.core.content.ContextCompat.getSystemService(
                        this@FlowDroidForegroundService,
                        android.app.NotificationManager::class.java,
                    ) ?: return@collect

                    // Always update the FGS notification on its dedicated channel.
                    nm.notify(NOTIF_ID, buildFgsNotification(status))

                    // Side-channel high-importance alert on RED only.
                    if (status == OverallStatus.RED) {
                        nm.notify(NOTIF_ID_ALERT, buildAlertNotification())
                    } else {
                        nm.cancel(NOTIF_ID_ALERT)
                    }
                }
        }
    }

    /** The persistent foreground-service notification. Always on [CHANNEL_FGS]. */
    private fun buildFgsNotification(status: OverallStatus): Notification {
        val pi = openAppPendingIntent()

        val (text, priority) = when (status) {
            OverallStatus.GREEN, OverallStatus.UNKNOWN -> {
                getString(R.string.fgs_notification_text_ok) to NotificationCompat.PRIORITY_LOW
            }
            OverallStatus.AMBER -> {
                getString(R.string.fgs_notification_text_degraded) to NotificationCompat.PRIORITY_DEFAULT
            }
            OverallStatus.RED -> {
                // FGS line stays low-key; the side-channel alert carries the buzz.
                getString(R.string.fgs_notification_text_critical) to NotificationCompat.PRIORITY_LOW
            }
        }

        return NotificationCompat.Builder(this, NotificationChannelManager.CHANNEL_FGS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(text)
            .setOngoing(true)                       // FGS notif is always sticky.
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_view, getString(R.string.fgs_action_health), pi)
            .build()
    }

    /** Side-channel HIGH-importance alert posted when overall status enters RED. */
    private fun buildAlertNotification(): Notification {
        val pi = openAppPendingIntent()
        return NotificationCompat.Builder(this, NotificationChannelManager.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.fgs_notification_text_critical))
            .setContentText(getString(R.string.fgs_notification_text_critical))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, REQ_OPEN, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "FgService"
        /** Static notification id for the persistent FGS post. */
        const val NOTIF_ID = 0xF1_60
        /** Notification id for the separate HIGH-importance alert posted when status is RED. */
        const val NOTIF_ID_ALERT = 0xF1_62
        private const val REQ_OPEN = 0xF1_61
        /** Heartbeat cadence — 60s, matching PLAN.md §4.1. */
        private const val HEARTBEAT_INTERVAL_MILLIS = 60_000L
    }
}
