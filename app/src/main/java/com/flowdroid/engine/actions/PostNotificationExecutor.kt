package com.flowdroid.engine.actions

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import com.flowdroid.service.NotificationChannelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Executor for [Action.PostNotification].
 *
 * Why a deterministic id (`flow.id.hashCode().absoluteValue`):
 *  - Re-posting from the same flow should UPDATE the existing notification, not pile up new
 *    ones in the shade. Using `flow.id.hashCode()` gives stable identity across executions
 *    without persisting an id mapping.
 *  - `absoluteValue` because `NotificationManager.notify` accepts an `Int`, and a negative id
 *    is legal but tooling assumes positives.
 *
 * Channel: we always post on [NotificationChannelManager.CHANNEL_FLOWS] (importance DEFAULT).
 * The channel is created idempotently by `NotificationChannelManager.ensureChannels` from the
 * Application; we don't re-create here.
 *
 * Error mapping:
 *  - [SecurityException] → [ExecutionError.PermissionMissing]("POST_NOTIFICATIONS"). On
 *    Android 13+ the runtime permission is required to post.
 */
@Singleton
class PostNotificationExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.PostNotification> {

    override val actionClass: Class<Action.PostNotification> =
        Action.PostNotification::class.java

    override suspend fun execute(
        action: Action.PostNotification,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val title = magicText.expandOrEmpty(action.title, context.variables)
        val text = magicText.expandOrEmpty(action.text, context.variables)

        val notif = NotificationCompat.Builder(
            this.context,
            NotificationChannelManager.CHANNEL_FLOWS,
        )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // Stable id: same flow → updates existing notification.
        val notifId = context.flow.id.hashCode().absoluteValue

        return try {
            NotificationManagerCompat.from(this.context).notify(notifId, notif)
            logger.info(
                TAG, "posted notification",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "notifId" to notifId,
            )
            Outcome.ok(Unit)
        } catch (e: SecurityException) {
            Outcome.err(ExecutionError.PermissionMissing("POST_NOTIFICATIONS"), e)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            Outcome.err(
                ExecutionError.SystemFailure(t.message ?: "notify failed"),
                t,
            )
        }
    }

    companion object {
        private const val TAG = "Action.Post"
    }
}
