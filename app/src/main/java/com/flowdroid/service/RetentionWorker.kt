package com.flowdroid.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.repo.FlowRunRepository
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.LogRepository
import com.flowdroid.common.repo.NotificationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic clean-up of `log_entries` / `notification_events` / `health_events` older than
 * [RETENTION_MILLIS] (2 days). Runs every 6 hours via WorkManager; each tick is a cheap
 * `DELETE WHERE timestampMillis < cutoff` per table.
 *
 * Why a separate worker instead of folding into [WatchdogWorker]:
 *  - The watchdog runs every 15 minutes; pruning every 15 minutes is wasteful churn against the
 *    db with no user-visible benefit.
 *  - Retention is a slow-moving concern (rows fall off the 2-day window once a day) — 6 hours is
 *    plenty.
 *
 * Always returns `Result.success()` so WorkManager keeps the schedule alive, even if individual
 * repository calls fail (logged via [StructuredLogger]).
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val logRepo: LogRepository,
    private val notificationRepo: NotificationRepository,
    private val healthRepo: HealthRepository,
    private val flowRunRepo: FlowRunRepository,
    private val clock: Clock,
    private val logger: StructuredLogger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = clock.nowMillis() - RETENTION_MILLIS
        prune("log", cutoff) { logRepo.pruneOlderThan(cutoff) }
        prune("notif", cutoff) { notificationRepo.pruneOlderThan(cutoff) }
        prune("health", cutoff) { healthRepo.pruneOlderThan(cutoff) }
        prune("flow_run", cutoff) { flowRunRepo.pruneOlderThan(cutoff) }
        return Result.success()
    }

    private suspend inline fun prune(
        kind: String,
        cutoff: Long,
        block: () -> Outcome<Int, *>,
    ) {
        try {
            when (val r = block()) {
                is Outcome.Ok -> logger.info(TAG, "pruned $kind rows",
                    "kind" to kind, "cutoffMillis" to cutoff, "deleted" to r.value)
                is Outcome.Err -> logger.warn(TAG, "prune $kind failed", null,
                    "kind" to kind, "error" to r.error.toString())
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "prune $kind threw", t, "kind" to kind)
        }
    }

    companion object {
        private const val TAG = "RetentionWorker"
        const val RETENTION_DAYS: Long = 2
        const val RETENTION_MILLIS: Long = RETENTION_DAYS * 24 * 60 * 60 * 1_000L
        const val WORK_NAME = "flowdroid_retention"

        /**
         * Schedule the periodic prune. Idempotent (KEEP policy) so calling at every
         * [com.flowdroid.common.service.ServiceController.bootstrap] is safe — WorkManager will
         * not duplicate the work request.
         */
        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<RetentionWorker>(
                repeatInterval = 6, repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }
    }
}
