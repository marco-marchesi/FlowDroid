package com.flowdroid.platform.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.flowdroid.common.Clock
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.DayOfWeek
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.TimeRange
import com.flowdroid.common.flow.Trigger
import com.flowdroid.service.TimeTriggerReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms / disarms / re-arms time-based triggers ([Trigger.TimeOfDay], [Trigger.Interval]) for
 * enabled flows.
 *
 * Storage model: we deliberately don't persist pending alarms — [AlarmManager] owns that. Each
 * (flowId, triggerIndex) pair maps to a stable `requestCode = flowId.hashCode() xor index`
 * which lets us recover and cancel the [PendingIntent] without keeping our own table.
 *
 * On boot, [com.flowdroid.service.BootReceiver] should kick [syncAllEnabled] to re-arm all flows
 * since AlarmManager loses its registered alarms across reboot.
 */
@Singleton
class TimeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val logger: StructuredLogger,
    private val repo: FlowRepository,
) {

    private val alarmManager: AlarmManager? get() = context.getSystemService()

    /**
     * Arm every time-based trigger in [flow]. Re-arming is safe: [PendingIntent.FLAG_UPDATE_CURRENT]
     * makes the call idempotent (the second one replaces the first), and WorkManager `KEEP`
     * policy preserves an in-flight periodic worker.
     */
    suspend fun arm(flow: Flow) {
        if (!flow.enabled) {
            logger.debug(TAG, "arm: flow disabled — skipping", "flowId" to flow.id)
            return
        }
        flow.triggers.forEachIndexed { index, trigger ->
            try {
                when (trigger) {
                    is Trigger.TimeOfDay -> armTimeOfDay(flow.id, index, trigger)
                    is Trigger.Interval -> armInterval(flow.id, index, trigger)
                    else -> Unit
                }
            } catch (t: Throwable) {
                if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
                logger.error(TAG, "arm: failed for trigger", t,
                    "flowId" to flow.id, "triggerIndex" to index,
                    "triggerType" to trigger::class.java.simpleName)
            }
        }
    }

    private fun armTimeOfDay(flowId: String, index: Int, trigger: Trigger.TimeOfDay) {
        val am = alarmManager ?: run {
            logger.warn(TAG, "armTimeOfDay: no AlarmManager", null, "flowId" to flowId)
            return
        }
        val nowMs = clock.nowMillis()
        val nextFire = nextFireMillis(
            nowMillis = nowMs,
            hour = trigger.hour,
            minute = trigger.minute,
            daysOfWeek = trigger.daysOfWeek,
            tz = TimeZone.getDefault(),
        )
        val pi = buildPendingIntent(flowId, index, "TimeOfDay") ?: run {
            logger.warn(TAG, "armTimeOfDay: buildPendingIntent returned null", null,
                "flowId" to flowId, "triggerIndex" to index)
            return
        }
        scheduleExactOrFallback(am, nextFire, pi, flowId, index)
        logger.info(TAG, "armed TimeOfDay",
            "flowId" to flowId, "triggerIndex" to index,
            "hour" to trigger.hour, "minute" to trigger.minute,
            "nextFireMs" to nextFire, "delayMs" to (nextFire - nowMs))
    }

    private fun armInterval(flowId: String, index: Int, trigger: Trigger.Interval) {
        val minutes = trigger.intervalMinutes
        if (minutes >= 15) {
            val data = workDataOf(
                IntervalTriggerWorker.KEY_FLOW_ID to flowId,
                IntervalTriggerWorker.KEY_ACTIVE_WINDOW to encodeWindow(trigger.activeWindow),
            )
            val req = PeriodicWorkRequestBuilder<IntervalTriggerWorker>(
                minutes.toLong(), TimeUnit.MINUTES,
            ).setInputData(data).build()
            val name = uniqueIntervalWorkName(flowId, index)
            // KEEP: do not reset the timer on re-arm.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                name, ExistingPeriodicWorkPolicy.KEEP, req,
            )
            logger.info(TAG, "armed Interval via WorkManager",
                "flowId" to flowId, "triggerIndex" to index,
                "intervalMinutes" to minutes, "workName" to name)
        } else {
            // <15min: WorkManager refuses periodic. Use AlarmManager.setRepeating — battery-unfriendly,
            // may be throttled / inexact on aggressive OEMs. Documented in the editor.
            val am = alarmManager ?: run {
                logger.warn(TAG, "armInterval: no AlarmManager", null, "flowId" to flowId)
                return
            }
            val intervalMs = minutes.coerceAtLeast(1) * 60_000L
            val first = clock.nowMillis() + intervalMs
            val pi = buildPendingIntent(flowId, index, "Interval") ?: run {
                logger.warn(TAG, "armInterval: buildPendingIntent returned null", null,
                    "flowId" to flowId, "triggerIndex" to index)
                return
            }
            try {
                am.setRepeating(AlarmManager.RTC_WAKEUP, first, intervalMs, pi)
                logger.warn(TAG, "armed sub-15min Interval via AlarmManager.setRepeating (battery-unfriendly)", null,
                    "flowId" to flowId, "triggerIndex" to index, "intervalMinutes" to minutes)
            } catch (t: Throwable) {
                if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
                logger.error(TAG, "armInterval: setRepeating failed", t,
                    "flowId" to flowId, "triggerIndex" to index)
            }
        }
    }

    private fun scheduleExactOrFallback(
        am: AlarmManager,
        whenMs: Long,
        pi: PendingIntent,
        flowId: String,
        index: Int,
    ) {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { am.canScheduleExactAlarms() } catch (t: Throwable) {
                if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
                false
            }
        } else true

        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            } else {
                logger.warn(TAG, "exact alarms not permitted — using setAndAllowWhileIdle (inexact)", null,
                    "flowId" to flowId, "triggerIndex" to index)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            // setExactAndAllowWhileIdle can throw SecurityException on API 31+ if the user revokes
            // the special permission between our check and the call. Fall back to inexact.
            logger.warn(TAG, "exact alarm scheduling threw — falling back to inexact", t,
                "flowId" to flowId, "triggerIndex" to index)
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            } catch (t2: Throwable) {
                if (t2 is OutOfMemoryError || t2 is kotlin.coroutines.cancellation.CancellationException) throw t2
                logger.error(TAG, "fallback setAndAllowWhileIdle also failed", t2,
                    "flowId" to flowId, "triggerIndex" to index)
            }
        }
    }

    /**
     * Cancel any alarms + WorkManager periodic work that may have been armed for [flowId].
     * Iterates `0..MAX_TRIGGERS-1` since we don't keep our own registry — at this many trigger
     * slots per flow the loop is cheap.
     */
    fun disarm(flowId: String) {
        val am = alarmManager
        val wm = WorkManager.getInstance(context)
        for (index in 0 until MAX_TRIGGERS) {
            if (am != null) {
                val tag = "Disarm"
                // We don't know which kind was armed at this index, but the PendingIntent shape
                // only depends on requestCode, so any matching one cancels regardless of extras.
                runCatchingLogged(tag, flowId, index) {
                    val pi = buildPendingIntent(flowId, index, kind = null, flagsOverride =
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    if (pi != null) {
                        am.cancel(pi)
                        pi.cancel()
                    }
                }
            }
            runCatchingLogged("DisarmWork", flowId, index) {
                wm.cancelUniqueWork(uniqueIntervalWorkName(flowId, index))
            }
        }
        logger.info(TAG, "disarmed", "flowId" to flowId)
    }

    /**
     * Snapshot the current flow list once, disarm everything we know about, then re-arm enabled
     * flows. Intended for boot. Does not subscribe to ongoing changes — that's the lifecycle
     * observer's job.
     */
    suspend fun syncAllEnabled() {
        val flows = try {
            repo.observeAll().first()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "syncAllEnabled: could not read flows", t)
            return
        }
        for (flow in flows) disarm(flow.id)
        for (flow in flows) {
            if (flow.enabled) {
                try {
                    arm(flow)
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
                    logger.error(TAG, "syncAllEnabled: arm failed", t, "flowId" to flow.id)
                }
            }
        }
        logger.info(TAG, "syncAllEnabled finished", "flowCount" to flows.size)
    }

    private fun buildPendingIntent(
        flowId: String,
        triggerIndex: Int,
        kind: String?,
        flagsOverride: Int? = null,
    ): PendingIntent? {
        val intent = Intent(context, TimeTriggerReceiver::class.java).apply {
            putExtra(EXTRA_FLOW_ID, flowId)
            putExtra(EXTRA_TRIGGER_INDEX, triggerIndex)
            if (kind != null) putExtra(EXTRA_TRIGGER_KIND, kind)
            // A distinct action makes the Intent's filterEquals discriminating across triggers
            // sharing the same component — defensive, since requestCode already disambiguates.
            action = "com.flowdroid.action.TIME_TRIGGER.$flowId.$triggerIndex"
        }
        val requestCode = requestCodeFor(flowId, triggerIndex)
        val flags = flagsOverride
            ?: (PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    @Suppress("ReturnCount")
    private inline fun runCatchingLogged(label: String, flowId: String, index: Int, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.warn(TAG, "$label step threw", t, "flowId" to flowId, "triggerIndex" to index)
        }
    }

    companion object {
        private const val TAG = "TimeScheduler"
        const val MAX_TRIGGERS = 16

        const val EXTRA_FLOW_ID = "com.flowdroid.extra.FLOW_ID"
        const val EXTRA_TRIGGER_INDEX = "com.flowdroid.extra.TRIGGER_INDEX"
        const val EXTRA_TRIGGER_KIND = "com.flowdroid.extra.TRIGGER_KIND"

        fun requestCodeFor(flowId: String, triggerIndex: Int): Int =
            flowId.hashCode() xor triggerIndex

        fun uniqueIntervalWorkName(flowId: String, triggerIndex: Int): String =
            "flowdroid.interval.$flowId.$triggerIndex"

        fun encodeWindow(window: TimeRange?): String? = window?.let {
            "${it.startHour}:${it.startMinute}:${it.endHour}:${it.endMinute}"
        }

        fun decodeWindow(raw: String?): TimeRange? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(':')
            if (parts.size != 4) return null
            return try {
                TimeRange(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * Pure, side-effect-free computation of the next wall-clock millis at which a TimeOfDay
 * trigger should fire.
 *
 * Rules:
 *  - HH:MM is interpreted in the device-local [tz].
 *  - If [daysOfWeek] is empty it's treated as "every day".
 *  - If today is allowed and HH:MM is strictly in the future → today.
 *  - Otherwise walk forward day-by-day (up to 7) until we hit an allowed weekday.
 *  - Uses [Calendar] which natively handles DST: setting HOUR_OF_DAY across a "spring forward"
 *    falls into the wall-clock interpretation chosen by the JDK (forward jump → the resolved
 *    instant skips ahead; "fall back" → first occurrence is picked). Tests pin this.
 */
internal fun nextFireMillis(
    nowMillis: Long,
    hour: Int,
    minute: Int,
    daysOfWeek: Set<DayOfWeek>,
    tz: TimeZone,
): Long {
    require(hour in 0..23) { "hour out of range: $hour" }
    require(minute in 0..59) { "minute out of range: $minute" }

    val effective = if (daysOfWeek.isEmpty()) DayOfWeek.entries.toSet() else daysOfWeek

    val cal = Calendar.getInstance(tz)
    cal.timeInMillis = nowMillis
    // Start with today @ HH:MM.
    cal.set(Calendar.HOUR_OF_DAY, hour)
    cal.set(Calendar.MINUTE, minute)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)

    // Walk up to 8 days (today + 7) — guaranteed to hit an allowed weekday since the set is non-empty.
    for (i in 0..7) {
        val dow = calendarDayToEnum(cal.get(Calendar.DAY_OF_WEEK))
        if (dow in effective && cal.timeInMillis > nowMillis) {
            return cal.timeInMillis
        }
        cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    // Theoretically unreachable; safety net.
    return cal.timeInMillis
}

private fun calendarDayToEnum(calDay: Int): DayOfWeek = when (calDay) {
    Calendar.MONDAY -> DayOfWeek.MON
    Calendar.TUESDAY -> DayOfWeek.TUE
    Calendar.WEDNESDAY -> DayOfWeek.WED
    Calendar.THURSDAY -> DayOfWeek.THU
    Calendar.FRIDAY -> DayOfWeek.FRI
    Calendar.SATURDAY -> DayOfWeek.SAT
    else -> DayOfWeek.SUN
}

