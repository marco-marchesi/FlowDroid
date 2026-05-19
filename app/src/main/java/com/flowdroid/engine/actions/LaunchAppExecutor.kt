package com.flowdroid.engine.actions

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 * Executor for [Action.LaunchApp].
 *
 * Why two intent shapes:
 *  - If the user specifies `activityClass`, we build an explicit `Intent(ComponentName(...))`.
 *    This is what advanced users want — pinning to a specific entry point regardless of what
 *    the launcher would resolve.
 *  - Otherwise we ask the PackageManager for the launcher activity. This is the "open app"
 *    button — works for any installed app and respects the user's chosen default activity.
 *
 * `FLAG_ACTIVITY_NEW_TASK` is required because we're starting an activity from a non-activity
 * context (the engine runs from a background coroutine on the application context).
 *
 * Error mapping:
 *  - [ActivityNotFoundException] → [ExecutionError.TargetNotFound] (the app/activity is not
 *    installed, or the activity name is wrong).
 *  - [SecurityException] → [ExecutionError.PermissionMissing] — typically thrown when launching
 *    components in restricted packages.
 *  - PackageManager returning null launch intent → [ExecutionError.TargetNotFound].
 */
@Singleton
class LaunchAppExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.LaunchApp> {

    override val actionClass: Class<Action.LaunchApp> = Action.LaunchApp::class.java

    override suspend fun execute(
        action: Action.LaunchApp,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val pkg = magicText.expandOrEmpty(action.packageName, context.variables)
        if (pkg.isBlank()) {
            return Outcome.err(
                ExecutionError.InvalidParam("packageName", "resolved to empty"),
            )
        }
        val activity = action.activityClass
            ?.let { magicText.expandOrEmpty(it, context.variables) }
            ?.takeIf { it.isNotBlank() }

        val intent: Intent = if (activity != null) {
            Intent().apply {
                component = ComponentName(pkg, activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            val launch = this.context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return Outcome.err(
                    ExecutionError.TargetNotFound("launchIntent:$pkg"),
                )
            launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }

        return try {
            this.context.startActivity(intent)
            logger.info(
                TAG, "launched app",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "package" to pkg,
                "activity" to (activity ?: "<launcher>"),
            )
            Outcome.ok(Unit)
        } catch (e: ActivityNotFoundException) {
            Outcome.err(
                ExecutionError.TargetNotFound("activity:$pkg/${activity ?: "<launcher>"}"),
                e,
            )
        } catch (e: SecurityException) {
            Outcome.err(ExecutionError.PermissionMissing("startActivity:$pkg"), e)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            Outcome.err(
                ExecutionError.SystemFailure(t.message ?: "startActivity failed"),
                t,
            )
        }
    }

    companion object {
        private const val TAG = "Action.Launch"
    }
}
