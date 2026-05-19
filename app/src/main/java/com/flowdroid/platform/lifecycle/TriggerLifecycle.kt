package com.flowdroid.platform.lifecycle

import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.Trigger
import com.flowdroid.platform.schedule.TimeScheduler
import com.flowdroid.platform.webhook.WebhookServer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "which non-notification triggers are armed right now".
 *
 * Observes [FlowRepository.observeAll] and on every emission diffs the previous snapshot
 * against the new one, calling into [TimeScheduler] and [WebhookServer] to arm/disarm
 * triggers so the armed state mirrors the persisted enabled-flow state.
 *
 * Started once by [com.flowdroid.FlowDroidApplication] after the DI graph is ready.
 *
 * Notes:
 * - Disabled flows have ALL their triggers disarmed (the engine wouldn't fire them anyway,
 *   but freeing scheduler slots is courteous to the OS).
 * - Re-arming the same trigger is a no-op for `TimeScheduler` (idempotent), and a re-register
 *   for `WebhookServer` (overwrites the registry entry).
 * - Boot recovery: BootReceiver also calls `syncAllEnabled()` on both schedulers directly,
 *   which is redundant once observe() catches up but ensures we don't depend on the flow
 *   subscription being established before the first time alarm needs to fire.
 */
@Singleton
class TriggerLifecycle @Inject constructor(
    private val repository: FlowRepository,
    private val timeScheduler: TimeScheduler,
    private val webhookServer: WebhookServer,
    private val logger: StructuredLogger,
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("TriggerLifecycle"),
    )

    @Volatile private var job: Job? = null

    /**
     * Begin observing the repository. Idempotent — calling twice does not duplicate the
     * subscription. Cancellation is via [shutdown].
     */
    fun start() {
        if (job?.isActive == true) return
        logger.info(TAG, "starting trigger lifecycle subscription")
        job = scope.launch {
            try {
                // Track the previous snapshot's flow ids so we can detect removals.
                var previousIds: Set<String> = emptySet()
                repository.observeAll().collectLatest { flows ->
                    syncOne(previousIds, flows)
                    previousIds = flows.map { it.id }.toSet()
                }
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                logger.warn(TAG, "lifecycle subscription crashed; restarting", t)
                // Self-heal: relaunch after a beat.
                kotlinx.coroutines.delay(2_000L)
                start()
            }
        }
    }

    /** Stop observing. Used by tests and ServiceController.shutdown. */
    fun shutdown() {
        job?.cancel()
        job = null
        logger.info(TAG, "trigger lifecycle stopped")
    }

    private suspend fun syncOne(previousIds: Set<String>, current: List<Flow>) {
        // 1. Disarm flows that disappeared since last snapshot.
        val currentIds = current.map { it.id }.toSet()
        for (removedId in (previousIds - currentIds)) {
            disarm(removedId)
        }

        // 2. For each current flow: arm if enabled, disarm if disabled.
        for (flow in current) {
            if (flow.enabled) arm(flow) else disarm(flow.id)
        }

        // 3. Start/stop the webhook server based on whether anyone wants it.
        webhookServer.startIfNeeded()
    }

    private suspend fun arm(flow: Flow) {
        try {
            timeScheduler.arm(flow)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "timeScheduler.arm failed", t, "flowId" to flow.id)
        }
        for (trigger in flow.triggers) {
            if (trigger is Trigger.Webhook) {
                try {
                    webhookServer.register(flow.id, trigger)
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError ||
                        t is kotlin.coroutines.cancellation.CancellationException
                    ) throw t
                    logger.warn(TAG, "webhookServer.register failed", t,
                        "flowId" to flow.id, "path" to trigger.path)
                }
            }
        }
    }

    private fun disarm(flowId: String) {
        try { timeScheduler.disarm(flowId) } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "timeScheduler.disarm failed", t, "flowId" to flowId)
        }
        try { webhookServer.unregister(flowId) } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "webhookServer.unregister failed", t, "flowId" to flowId)
        }
    }

    companion object {
        private const val TAG = "TriggerLifecycle"
    }
}
