package com.flowdroid.engine.actions

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.expandOrEmpty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.DateFormat]. Formats a Unix-millis timestamp with a SimpleDateFormat
 * pattern. Empty `timestamp` → current wall-clock millis. Invalid pattern → InvalidParam (don't
 * silently store something the user didn't ask for).
 */
@Singleton
class DateFormatExecutor @Inject constructor(
    private val magicText: MagicTextEngine,
    private val clock: Clock,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.DateFormat> {

    override val actionClass: Class<Action.DateFormat> = Action.DateFormat::class.java

    override suspend fun execute(
        action: Action.DateFormat,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val varName = action.intoVar.trim()
        if (varName.isEmpty()) {
            return Outcome.err(ExecutionError.InvalidParam("intoVar", "required"))
        }
        val tsRaw = magicText.expandOrEmpty(action.timestamp, context.variables).trim()
        val millis = if (tsRaw.isEmpty()) clock.nowMillis() else tsRaw.toLongOrNull() ?: clock.nowMillis()
        val pattern = action.pattern.ifBlank { "yyyy-MM-dd HH:mm:ss" }
        return try {
            val formatter = SimpleDateFormat(pattern, Locale.getDefault())
            val out = formatter.format(Date(millis))
            context.variables[varName] = out
            logger.info(TAG, "formatted date",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "pattern" to pattern,
                "output" to out)
            Outcome.ok(Unit)
        } catch (t: IllegalArgumentException) {
            logger.warn(TAG, "invalid date pattern", t, "pattern" to pattern)
            Outcome.err(ExecutionError.InvalidParam("pattern", "invalid: ${t.message}"))
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "date format threw", t)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "date format failed"), t)
        }
    }

    companion object { private const val TAG = "Action.DateFormat" }
}
