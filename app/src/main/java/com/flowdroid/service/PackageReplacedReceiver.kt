package com.flowdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowdroid.common.Clock
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.service.ServiceController
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
 * Handles MY_PACKAGE_REPLACED.
 *
 * When the user upgrades FlowDroid the OS kills the old process; we must re-bootstrap so the
 * foreground service is back up and the listener gets rebound. Same async pattern as
 * [BootReceiver].
 */
@AndroidEntryPoint
class PackageReplacedReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: ServiceController
    @Inject lateinit var healthRepo: HealthRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var logger: StructuredLogger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        logger.info(TAG, "Package replaced — re-bootstrapping")

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("PkgReplaced"))
        scope.launch {
            try {
                withTimeoutOrNull(MAX_WORK_MILLIS) {
                    runCatchingLogged {
                        healthRepo.insert(
                            HealthEvent(
                                timestampMillis = clock.nowMillis(),
                                kind = HealthEvent.Kind.PACKAGE_REPLACED,
                                message = "MY_PACKAGE_REPLACED",
                                outcome = null,
                            )
                        )
                    }
                    runCatchingLogged { controller.bootstrap() }
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
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
        private const val TAG = "PkgReplaced"
        private const val MAX_WORK_MILLIS = 5_000L
    }
}
