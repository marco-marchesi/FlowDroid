package com.flowdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.platform.schedule.TimeScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Receives `AlarmManager` broadcasts from [TimeScheduler] and dispatches to the engine.
 *
 * After firing, re-arms the next occurrence by calling [TimeScheduler.arm] on the loaded flow —
 * AlarmManager exact-alarm is one-shot, so a recurring TimeOfDay trigger relies on this loop.
 * (Interval-via-WorkManager re-arms itself; sub-15min Interval-via-setRepeating is recurring at
 * the AlarmManager level so no re-arm needed.)
 *
 * 5-second budget — well under the BroadcastReceiver hard limit (~10s on most OEMs).
 */
@AndroidEntryPoint
class TimeTriggerReceiver : BroadcastReceiver() {

    @Inject lateinit var flowEngine: FlowEngine
    @Inject lateinit var scheduler: TimeScheduler
    @Inject lateinit var repo: FlowRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var logger: StructuredLogger

    override fun onReceive(context: Context, intent: Intent) {
        val flowId = intent.getStringExtra(TimeScheduler.EXTRA_FLOW_ID) ?: run {
            logger.warn(TAG, "no flowId extra — ignoring", null)
            return
        }
        val triggerKind = intent.getStringExtra(TimeScheduler.EXTRA_TRIGGER_KIND) ?: "TimeOfDay"
        logger.info(TAG, "alarm received", "flowId" to flowId, "kind" to triggerKind)

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("TimeTrigger"))
        scope.launch {
            try {
                withTimeoutOrNull(MAX_WORK_MILLIS) {
                    runCatchingLogged("fire") {
                        flowEngine.onTriggered(
                            flowId,
                            TriggerEvent.ScheduledTime(
                                firedAtMillis = clock.nowMillis(),
                                triggerKind = triggerKind,
                            ),
                        )
                    }
                    // Re-arm the next occurrence. Only TimeOfDay needs this strictly, but arm() is
                    // idempotent — it walks all triggers and Interval-via-WM uses KEEP policy, so
                    // calling unconditionally is safe.
                    if (triggerKind == "TimeOfDay") {
                        runCatchingLogged("rearm") {
                            when (val r = repo.get(flowId)) {
                                is Outcome.Ok -> r.value?.let { scheduler.arm(it) }
                                is Outcome.Err -> logger.warn(TAG, "rearm: repo.get failed", null,
                                    "flowId" to flowId, "error" to r.error)
                            }
                        }
                    }
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private inline fun runCatchingLogged(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "step '$label' threw", t)
        }
    }

    companion object {
        private const val TAG = "TimeTriggerReceiver"
        private const val MAX_WORK_MILLIS = 5_000L
    }
}
