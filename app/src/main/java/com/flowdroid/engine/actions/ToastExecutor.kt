package com.flowdroid.engine.actions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
 * Executor for [Action.Toast]. Posts a brief on-screen Toast via the Android system.
 *
 * `Toast.makeText` MUST be called on a Looper thread; the engine runs actions on Dispatchers.IO,
 * which has no Looper. We hop to the main thread via a [Handler] before showing.
 */
@Singleton
class ToastExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val magicText: MagicTextEngine,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Toast> {

    override val actionClass: Class<Action.Toast> = Action.Toast::class.java

    override suspend fun execute(
        action: Action.Toast,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val resolved = magicText.expandOrEmpty(action.text, context.variables)
        if (resolved.isEmpty()) return Outcome.err(ExecutionError.InvalidParam("text", "empty"))
        return try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this.context,
                    resolved.take(MAX_TOAST_LEN),
                    if (action.longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
            logger.info(TAG, "toast shown",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "len" to resolved.length)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "toast threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "toast failed"), t)
        }
    }

    companion object {
        private const val TAG = "Action.Toast"
        // Toast truncates anything past ~200 chars in any case; we cap defensively.
        private const val MAX_TOAST_LEN = 200
    }
}
