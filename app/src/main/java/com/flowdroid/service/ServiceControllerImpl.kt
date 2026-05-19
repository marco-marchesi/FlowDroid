package com.flowdroid.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.service.ServiceController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [ServiceController] — the façade that bring the orchestrator up.
 *
 * `bootstrap()` is the single entry point used by [com.flowdroid.FlowDroidApplication],
 * [BootReceiver] and [PackageReplacedReceiver]. It is idempotent — calling repeatedly
 * is safe and cheap.
 *
 * `toggleNotificationListener()` is the heaviest escalation in the watchdog ladder:
 *   1. We mark the component DISABLED via [PackageManager.setComponentEnabledSetting].
 *   2. After a 1s delay (Handler on the main looper — the work is trivial and we
 *      don't want a coroutine context dependency here) we re-mark it ENABLED.
 *   3. We then call [NotificationListenerService.requestRebind] to nudge the OS into
 *      rebinding immediately rather than waiting for the next user interaction.
 *
 * Why disable+enable: on Samsung the OS occasionally caches a "lost" binding without
 * informing the listener. A component toggle invalidates that cache.
 */
@Singleton
class ServiceControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelManager: NotificationChannelManager,
    private val logger: StructuredLogger,
) : ServiceController {

    override fun bootstrap() {
        try {
            channelManager.ensureChannels(context)
            val intent = Intent(context, FlowDroidForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
            WatchdogWorker.schedulePeriodic(context)
            RetentionWorker.enqueue(context)
            logger.info(TAG, "bootstrap completed")
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "bootstrap failed", t)
        }
    }

    override fun toggleNotificationListener() {
        try {
            val pm = context.packageManager
            val component = listenerComponent()
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            logger.warn(TAG, "Toggled listener component to DISABLED")
            // Re-enable after a short delay. Handler is fine — we only need a one-shot.
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    try {
                        pm.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                        logger.info(TAG, "Re-enabled listener component")
                        requestRebindSafe(component)
                    } catch (t: Throwable) {
                        // CRITICAL: Cancellation/OOM rethrows so we don't silently strand the
                        // listener DISABLED. Without this, a swallowed Cancellation in the
                        // re-enable path leaves the user with permanently revoked notification
                        // access until a force-stop or reinstall.
                        if (t is OutOfMemoryError ||
                            t is kotlin.coroutines.cancellation.CancellationException
                        ) throw t
                        logger.error(TAG, "Re-enable step failed", t)
                    }
                },
                TOGGLE_REENABLE_DELAY_MILLIS,
            )
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "toggleNotificationListener failed", t)
        }
    }

    override fun requestNotificationListenerRebind() {
        requestRebindSafe(listenerComponent())
    }

    override fun shutdown() {
        try {
            context.stopService(Intent(context, FlowDroidForegroundService::class.java))
            WatchdogWorker.cancel(context)
            logger.info(TAG, "shutdown completed")
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "shutdown failed", t)
        }
    }

    private fun requestRebindSafe(component: ComponentName) {
        try {
            NotificationListenerService.requestRebind(component)
            logger.info(TAG, "requestRebind issued")
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.error(TAG, "requestRebind threw", t)
        }
    }

    private fun listenerComponent(): ComponentName =
        ComponentName(context, FlowDroidNotificationListenerService::class.java)

    companion object {
        private const val TAG = "ServiceCtrl"
        const val TOGGLE_REENABLE_DELAY_MILLIS = 1_000L
    }
}
