package com.flowdroid.platform.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.flowdroid.common.Clock
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.TimeRange
import com.flowdroid.common.flow.TriggerEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar

/**
 * Periodic worker enqueued by [TimeScheduler] for [com.flowdroid.common.flow.Trigger.Interval]
 * triggers with `intervalMinutes >= 15`.
 *
 * Reads `flowId` (required) and `activeWindow` (optional, encoded HH:MM:HH:MM) from the worker
 * input data. If an active window is configured and the current local time falls outside it,
 * we skip the fire silently — without that gate users can't ask for "every 15 min between 09:00
 * and 18:00".
 *
 * Always returns [Result.success] so WorkManager keeps the periodic schedule alive. A failure
 * here should not unschedule us.
 */
@HiltWorker
class IntervalTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val flowEngine: FlowEngine,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val flowId = inputData.getString(KEY_FLOW_ID)
        if (flowId.isNullOrBlank()) {
            // Bad input — nothing we can do, but don't fail (we'd be re-scheduled with the same data).
            return Result.success()
        }
        val window = TimeScheduler.decodeWindow(inputData.getString(KEY_ACTIVE_WINDOW))
        val now = clock.nowMillis()
        if (window != null && !isWithinWindow(now, window)) {
            // Quietly skip — within an active-window flow this is the common case.
            return Result.success()
        }

        try {
            flowEngine.onTriggered(
                flowId,
                TriggerEvent.ScheduledTime(firedAtMillis = now, triggerKind = "Interval"),
            )
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            // Swallow — we want WorkManager to keep scheduling regardless.
        }
        return Result.success()
    }

    companion object {
        const val KEY_FLOW_ID = "flowId"
        const val KEY_ACTIVE_WINDOW = "activeWindow"

        /**
         * Half-open window test in device-local time. If `end < start` (wraps midnight) the
         * window is treated as `[start, 24:00) ∪ [00:00, end)`.
         */
        internal fun isWithinWindow(nowMillis: Long, window: TimeRange): Boolean {
            val cal = Calendar.getInstance()
            cal.timeInMillis = nowMillis
            val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val startMins = window.startHour * 60 + window.startMinute
            val endMins = window.endHour * 60 + window.endMinute
            return if (startMins <= endMins) {
                nowMins in startMins until endMins
            } else {
                nowMins >= startMins || nowMins < endMins
            }
        }
    }
}
