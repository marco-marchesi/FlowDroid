package com.flowdroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.flowdroid.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of the app's [NotificationChannel] definitions.
 *
 * Why a dedicated singleton (rather than ad-hoc creation at the point of use):
 *  - Channel definitions need to be created idempotently *before* any service or worker
 *    attempts to post a notification. The FGS would silently fail to startForeground if its
 *    channel didn't exist.
 *  - Centralising names/descriptions/importance avoids drift between modules (the FGS,
 *    the watchdog and future flow notifications all share the same source of truth).
 *  - Android caches channel state in user-modifiable form once created. Re-creating a
 *    channel with the same id is a no-op except for fields the user has not customised —
 *    so [ensureChannels] is safely idempotent and cheap to call on every cold start.
 */
@Singleton
class NotificationChannelManager @Inject constructor() {

    /**
     * Create all channels FlowDroid needs. Idempotent — safe to call from any service
     * `onCreate`, from boot, and from the Application's bootstrap path.
     */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return

        val service = NotificationChannel(
            CHANNEL_FGS,
            context.getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.fgs_channel_description)
            setShowBadge(false)
            // Persistent FGS notification — never sound/vibrate.
            enableLights(false)
            enableVibration(false)
        }

        val flows = NotificationChannel(
            CHANNEL_FLOWS,
            context.getString(R.string.flows_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.flows_channel_description)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.alerts_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alerts_channel_description)
            // Critical health alerts: surface to user with sound/vibrate.
            enableVibration(true)
            enableLights(true)
        }

        // createNotificationChannels accepts a list and creates atomically.
        nm.createNotificationChannels(listOf(service, flows, alerts))
    }

    companion object {
        /** Low-importance channel for the orchestrator's persistent FGS notification. */
        const val CHANNEL_FGS = "flowdroid_service"

        /** Default-importance channel for user flows (future, Phase 1+). */
        const val CHANNEL_FLOWS = "flowdroid_flows"

        /** High-importance channel for critical health alerts (listener lost etc.). */
        const val CHANNEL_ALERTS = "flowdroid_alerts"
    }
}
