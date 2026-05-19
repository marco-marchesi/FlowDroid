package com.flowdroid.engine.actions

import android.app.ActivityManager
import android.content.Context
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
 * Executor for [Action.KillApp].
 *
 * Implementation note — this is intentionally best-effort:
 *  - We call [ActivityManager.killBackgroundProcesses] which **only** terminates processes that
 *    have no foreground component. It cannot kill a visible activity, nor a foreground service
 *    backed app. The editor surfaces this caveat to the user.
 *  - Rooted devices, OEM-privileged builds, or Device-Owner installs could escalate to
 *    `Runtime.exec("am force-stop $pkg")` — explicitly out of scope for the OSS build.
 *
 * Permission: the host manifest must declare
 * `<uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES"/>`.
 *
 * Error mapping:
 *  - Blank resolved package → [ExecutionError.InvalidParam].
 *  - [SecurityException] → [ExecutionError.PermissionMissing("android.permission.KILL_BACKGROUND_PROCESSES")].
 *  - Missing ACTIVITY_SERVICE (impossible on real devices; possible in tests) → [ExecutionError.SystemFailure].
 *  - Any other throwable → [ExecutionError.SystemFailure].
 *  - [OutOfMemoryError] and [kotlin.coroutines.cancellation.CancellationException] are rethrown.
 */
@Singleton
class KillAppExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.KillApp> {

    override val actionClass: Class<Action.KillApp> = Action.KillApp::class.java

    override suspend fun execute(
        action: Action.KillApp,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val pkg = magicText.expandOrEmpty(action.packageName, context.variables)
        if (pkg.isBlank()) {
            return Outcome.err(
                ExecutionError.InvalidParam("packageName", "resolved to empty"),
            )
        }

        logger.info(
            TAG, "killing background processes",
            "flowId" to context.flow.id,
            "execId" to context.executionId,
            "package" to pkg,
        )

        val am = this.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            logger.warn(
                TAG, "ActivityManager unavailable",
                null,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "package" to pkg,
            )
            return Outcome.err(ExecutionError.SystemFailure("ActivityManager unavailable"))
        }

        return try {
            am.killBackgroundProcesses(pkg)
            logger.info(
                TAG, "killBackgroundProcesses dispatched",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "package" to pkg,
            )
            Outcome.ok(Unit)
        } catch (e: SecurityException) {
            logger.warn(
                TAG, "missing KILL_BACKGROUND_PROCESSES permission",
                e,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "package" to pkg,
            )
            Outcome.err(
                ExecutionError.PermissionMissing("android.permission.KILL_BACKGROUND_PROCESSES"),
                e,
            )
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(
                TAG, "killBackgroundProcesses failed",
                t,
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "package" to pkg,
            )
            Outcome.err(
                ExecutionError.SystemFailure(t.message ?: "killBackgroundProcesses failed"),
                t,
            )
        }
    }

    companion object {
        private const val TAG = "Action.Kill"
    }
}
