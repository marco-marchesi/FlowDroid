package com.flowdroid.engine.actions

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executor for [Action.Vibrate]. One-shot pulse via [VibrationEffect.createOneShot] on API 26+
 * (which is everything we target — minSdk=29). The `VIBRATE` permission is declared in the
 * manifest; it's a normal permission that's auto-granted on install — no runtime prompt.
 */
@Singleton
class VibrateExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StructuredLogger,
) : ActionExecutor<Action.Vibrate> {

    override val actionClass: Class<Action.Vibrate> = Action.Vibrate::class.java

    override suspend fun execute(
        action: Action.Vibrate,
        context: ExecutionContext,
    ): Outcome<Unit, ExecutionError> {
        val duration = action.durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        return try {
            val vibrator = vibrator() ?: return Outcome.err(
                ExecutionError.SystemFailure("no Vibrator on this device"),
            )
            // Amplitude < 0 means "default amplitude"; otherwise clamp into the legal 1..255.
            val amp = if (action.amplitude < 0) VibrationEffect.DEFAULT_AMPLITUDE
                else action.amplitude.coerceIn(1, 255)
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amp))
            logger.info(TAG, "vibrated",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "durationMs" to duration,
                "amplitude" to amp)
            Outcome.ok(Unit)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "vibrate threw", t, "flowId" to context.flow.id)
            Outcome.err(ExecutionError.SystemFailure(t.message ?: "vibrate failed"), t)
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    companion object {
        private const val TAG = "Action.Vibrate"
        private const val MIN_DURATION_MS = 10L
        private const val MAX_DURATION_MS = 10_000L
    }
}
