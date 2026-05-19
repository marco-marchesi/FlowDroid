package com.flowdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowdroid.common.Clock
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.service.ServiceController
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
 * Receives BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / QUICKBOOT_POWERON and bootstraps the
 * foreground service. Without this the listener is bound by the OS automatically only on
 * the *next* user interaction, which on locked-out Samsung devices can be hours.
 *
 * Why [BroadcastReceiver.goAsync]:
 *  - We need to persist a [HealthEvent.Kind.BOOT_COMPLETED] entry to Room *and* start the
 *    foreground service. Both can take >10ms. `onReceive` runs on the main thread and is
 *    limited to ~10s — goAsync gives us a [BroadcastReceiver.PendingResult] which keeps the
 *    receiver alive across coroutine dispatch.
 *  - We hard-cap the work at 5s with [withTimeoutOrNull] so we never accidentally hold the
 *    receiver past its budget.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: ServiceController
    @Inject lateinit var healthRepo: HealthRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var logger: StructuredLogger
    @Inject lateinit var timeScheduler: TimeScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACCEPTED_ACTIONS) return

        logger.info(TAG, "Boot signal received", "action" to action)

        val pending = goAsync()
        // Tight-lifetime scope: created here, cancelled in finally. Avoids leaking a SupervisorJob
        // per boot signal (LOCKED_BOOT_COMPLETED + BOOT_COMPLETED arrive in pairs).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("BootReceiver"))
        scope.launch {
            try {
                withTimeoutOrNull(MAX_WORK_MILLIS) {
                    runCatchingLogged {
                        healthRepo.insert(
                            HealthEvent(
                                timestampMillis = clock.nowMillis(),
                                kind = HealthEvent.Kind.BOOT_COMPLETED,
                                message = action,
                                outcome = null,
                            )
                        )
                    }
                    runCatchingLogged { controller.bootstrap() }
                    // Re-arm time-based triggers — AlarmManager state is reset on boot.
                    // TriggerLifecycle will eventually catch up via the FlowRepository
                    // subscription, but explicit sync here ensures alarms restore even before
                    // the first repository emission.
                    runCatchingLoggedAsync { timeScheduler.syncAllEnabled() }
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun runCatchingLoggedAsync(block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.error(TAG, "Bootstrap async step threw", t)
        }
    }

    private inline fun runCatchingLogged(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
            logger.error(TAG, "Bootstrap step threw", t)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val MAX_WORK_MILLIS = 5_000L
        private val ACCEPTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
