package com.flowdroid.engine.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.OpenUrl]. Fires an Intent.ACTION_VIEW; when `targetPackage` is set, the
 * intent is constrained to that package (e.g. open the YouTube link only in the YouTube app).
 */
@Singleton
class OpenUrlExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.OpenUrl> {

    override val actionClass: Class<Action.OpenUrl> = Action.OpenUrl::class.java

    override suspend fun execute(
        action: Action.OpenUrl,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val url = magicText.expandOrEmpty(action.url, context.variables).trim()
        if (url.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("url", "empty"))
        val uri = try { Uri.parse(url) } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            return Outcome.err(ExecutionError.InvalidParam("url", "unparseable"))
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!action.targetPackage.isNullOrBlank()) {
                    setPackage(action.targetPackage.trim())
                }
            }
            this.context.startActivity(intent)
            logger.info(TAG, "open url",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "scheme" to uri.scheme.orEmpty(),
                "host" to uri.host.orEmpty(),
                "pkg" to (action.targetPackage ?: ""))
            Outcome.ok(Unit)
        } catch (t: android.content.ActivityNotFoundException) {
            logger.warn(TAG, "no activity to handle url", t, "url" to url)
            Outcome.err(ExecutionError.TargetNotFound("no activity for $url"))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "open url threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "open url failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.OpenUrl"
    }
}
