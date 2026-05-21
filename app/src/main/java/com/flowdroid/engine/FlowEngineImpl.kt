package com.flowdroid.engine

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ActionExecutor
import com.flowdroid.common.flow.ActionRunner
import com.flowdroid.common.flow.ExecutionContext
import com.flowdroid.common.flow.ExecutionError
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowEngine
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.domain.FlowRun
import com.flowdroid.common.domain.FlowRunActionResult
import com.flowdroid.common.flow.MagicTextBindings
import com.flowdroid.common.flow.MagicTextEngine
import com.flowdroid.common.flow.TriggerEvent
import com.flowdroid.common.repo.FlowRunRepository
import com.flowdroid.data.repo.NoOpFlowRunRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Default [FlowEngine] — the orchestration core for Phase 1 MVP.
 *
 * Why an injected executor map (`Map<Class<out Action>, ActionExecutor<*>>`):
 *  - Each concrete [Action] type has its own executor with its own dependencies (PackageManager,
 *    SbnCache, MagicText, etc.). Holding a registry indexed by the action's runtime class keeps
 *    the engine itself free of executor-specific imports — adding a new action type means
 *    declaring a new executor + one Hilt binding, no engine code change.
 *  - Hilt's multibinds (`@IntoMap` + a custom `@MapKey`) materialises this map at injection
 *    time. We use `Map<Class<out Action>, ...>` rather than `Map<KClass<...>, ...>` so the
 *    lookup at runtime is a single `action::class.java` indexing (no reflection cost).
 *
 * Execution strategy:
 *  - [onNotificationPosted] must return quickly (the listener thread is the only one reading
 *    notifications). We do the cheap matching synchronously, then launch a coroutine on our
 *    own scope to run actions.
 *  - Each action runs under a 30-second `withTimeoutOrNull`. The engine never lets a broken
 *    executor block subsequent actions indefinitely.
 *  - On error, `continueOnError` decides whether we keep walking the action list.
 *
 * Logging:
 *  - DEBUG on no-match (with the reason) — vital for "why didn't my flow fire" debugging.
 *  - INFO on match / debounce / execution start / each action result / execution finish.
 *  - WARN on debounce-denied is intentionally INFO not WARN — it is expected and benign.
 *  - ERROR on missing executor (programmer error: the user built a flow with an action type
 *    the engine doesn't know about).
 */
@Singleton
class FlowEngineImpl(
    private val repo: FlowRepository,
    private val magicText: MagicTextEngine,
    private val debouncer: Debouncer,
    private val executors: Map<Class<out Action>, @JvmSuppressWildcards ActionExecutor<*>>,
    private val logger: StructuredLogger,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where per-execution audit rows go. Defaults to [NoOpFlowRunRepository] so tests that
     * construct the engine without Hilt don't need to inject a repo. Production graph binds the
     * real [com.flowdroid.data.repo.FlowRunRepositoryImpl] via Hilt's [Inject] constructor below.
     */
    private val flowRunRepo: FlowRunRepository = NoOpFlowRunRepository,
) : FlowEngine, ActionRunner {

    /**
     * Hilt-friendly constructor. The production graph injects everything; tests can use the
     * primary constructor to override [dispatcher] with a test dispatcher.
     */
    @Inject constructor(
        repo: FlowRepository,
        magicText: MagicTextEngine,
        debouncer: Debouncer,
        executors: Map<Class<out Action>, @JvmSuppressWildcards ActionExecutor<*>>,
        logger: StructuredLogger,
        clock: Clock,
        flowRunRepo: FlowRunRepository,
    ) : this(repo, magicText, debouncer, executors, logger, clock, Dispatchers.IO, flowRunRepo)

    /**
     * Per-execution recorders, keyed by `executionId`. Populated by [executeFlow] at start and
     * removed at end. [runActions] looks up the recorder for the current execution to append
     * per-action results. Concurrent map because multiple flows can execute in parallel under
     * the engine's [scope].
     */
    private val recorders: ConcurrentHashMap<String, RunRecorder> = ConcurrentHashMap()

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineName("Engine")
    )

    override suspend fun onNotificationPosted(event: NotificationEvent) {
        val flows = when (val r = repo.enabledNotificationFlows()) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> {
                logger.warn(
                    TAG, "could not load enabled notification flows",
                    null,
                    "error" to r.error,
                )
                return
            }
        }

        if (flows.isEmpty()) return

        for (flow in flows) {
            // Phase 2: a flow has a LIST of triggers — fire if ANY of them matches.
            // We iterate, log per trigger for diagnostics, and stop at the first match.
            var matched: com.flowdroid.common.flow.Trigger? = null
            for ((index, trig) in flow.triggers.withIndex()) {
                val outcome = TriggerMatcher.matches(trig, event)
                when (outcome) {
                    is TriggerMatcher.MatchOutcome.NoMatch -> {
                        logger.debug(
                            TAG, "trigger no-match",
                            "flowId" to flow.id,
                            "triggerIndex" to index,
                            "reason" to outcome.reason,
                        )
                    }
                    is TriggerMatcher.MatchOutcome.InvalidRegex -> {
                        logger.warn(
                            TAG, "trigger has invalid regex; skipping",
                            null,
                            "flowId" to flow.id,
                            "triggerIndex" to index,
                            "field" to outcome.field,
                            "pattern" to outcome.pattern,
                        )
                    }
                    TriggerMatcher.MatchOutcome.Matched -> {
                        matched = trig
                    }
                }
                if (matched != null) break
            }

            if (matched == null) continue

            logger.info(
                TAG, "flow match",
                "flowId" to flow.id,
                "flowName" to flow.name,
                "package" to event.packageName,
            )
            val debounceWindow = (matched as? com.flowdroid.common.flow.Trigger.NotificationPosted)
                ?.debounceMillis ?: 0L
            val allowed = debouncer.tryFire(flow.id, clock.nowMillis(), debounceWindow)
            if (!allowed) {
                logger.info(
                    TAG, "flow debounced",
                    "flowId" to flow.id,
                    "flowName" to flow.name,
                    "windowMillis" to debounceWindow,
                )
                continue
            }

            scope.launch {
                executeFlow(flow, TriggerEvent.NotificationFired(event = event, sbnKey = event.sbnKey))
            }
        }
    }

    /**
     * External entry point used by [com.flowdroid.platform.schedule.TimeScheduler] and
     * [com.flowdroid.platform.webhook.WebhookServer]. Bypasses the notification matcher —
     * the caller has already proven the flow should fire.
     */
    override suspend fun onTriggered(flowId: String, event: TriggerEvent) {
        val flow = when (val r = repo.get(flowId)) {
            is Outcome.Ok -> r.value ?: run {
                logger.warn(TAG, "onTriggered: flow not found", null, "flowId" to flowId)
                return
            }
            is Outcome.Err -> {
                logger.warn(TAG, "onTriggered: get failed", null,
                    "flowId" to flowId, "error" to r.error)
                return
            }
        }
        if (!flow.enabled) {
            logger.debug(TAG, "onTriggered: flow disabled", "flowId" to flowId)
            return
        }
        // Light debounce — webhooks can be spammed and time triggers can theoretically double-fire.
        val allowed = debouncer.tryFire(flow.id, clock.nowMillis(), DEBOUNCE_EXTERNAL_MS)
        if (!allowed) {
            logger.info(TAG, "external trigger debounced", "flowId" to flowId)
            return
        }
        logger.info(TAG, "external trigger fire",
            "flowId" to flowId, "flowName" to flow.name, "kind" to event::class.simpleName.orEmpty())
        scope.launch { executeFlow(flow, event) }
    }

    /**
     * Runs all actions of a matched flow sequentially. Visible for test injection if needed.
     */
    internal suspend fun executeFlow(flow: Flow, triggerEvent: TriggerEvent) {
        val execId = "%08x".format(Random.nextInt())
        val variables = buildVariables(flow, triggerEvent, execId)
        val context = ExecutionContext(
            executionId = execId,
            flow = flow,
            triggerEvent = triggerEvent,
            variables = variables,
        )

        logger.info(
            TAG, "execution starting",
            "flowId" to flow.id,
            "flowName" to flow.name,
            "execId" to execId,
            "actionCount" to flow.actions.size,
        )

        // Recorder lifecycle: created here, removed at the end. runActions populates it
        // per top-level action; nested If/Loop/TryCatch reentrancies also append, which is OK —
        // their entries help the user see why an inner action failed.
        val startMillis = clock.nowMillis()
        val recorder = RunRecorder(
            flowId = flow.id,
            flowName = flow.name,
            executionId = execId,
            triggerKind = triggerEvent::class.simpleName.orEmpty(),
            startedAtMillis = startMillis,
        )
        recorders[execId] = recorder

        try {
            val stats = runActions(flow.actions, context)
            val endMillis = clock.nowMillis()

            logger.info(
                TAG, "execution finished",
                "flowId" to flow.id,
                "execId" to execId,
                "ok" to stats.okCount,
                "err" to stats.errCount,
                "aborted" to stats.aborted,
            )

            persistRun(recorder.toFlowRun(endMillis, stats))
        } finally {
            recorders.remove(execId)
        }
    }

    /**
     * Best-effort write to [flowRunRepo]. Persistence failure is logged and absorbed — the
     * engine NEVER lets a Room error abort a real flow execution.
     */
    private suspend fun persistRun(run: FlowRun) {
        try {
            when (val r = flowRunRepo.insert(run)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> logger.warn(
                    TAG, "flow-run insert failed",
                    null,
                    "flowId" to run.flowId,
                    "execId" to run.executionId,
                    "error" to r.error.toString(),
                )
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            logger.warn(TAG, "flow-run insert threw", t,
                "flowId" to run.flowId, "execId" to run.executionId)
        }
    }

    /**
     * Run an ordered list of actions against an existing [ExecutionContext]. Public so
     * control-flow executors (If, future Loop/TryCatch) can recurse into a sub-list without
     * spinning up a new execution context — variables set in the sub-list are visible to the
     * parent flow's later actions, which is what users expect.
     */
    override suspend fun runActions(
        actions: List<Action>,
        context: ExecutionContext,
    ): ActionRunner.RunStats {
        var okCount = 0
        var errCount = 0
        var aborted = false

        // null when called outside of any executeFlow (e.g. directly from a unit test). In that
        // case we just don't record — same behaviour as before Phase 12.
        val recorder = recorders[context.executionId]

        for ((index, action) in actions.withIndex()) {
            val executor = executors[action::class.java]
            if (executor == null) {
                logger.error(
                    TAG, "no executor for action class",
                    null,
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "actionIndex" to index,
                    "actionClass" to action::class.java.name,
                )
                errCount++
                recorder?.append(
                    FlowRunActionResult(
                        index = index,
                        actionClass = action::class.java.simpleName,
                        label = action.label,
                        status = "missing_executor",
                        durationMs = 0L,
                        errorMessage = "no executor for ${action::class.java.simpleName}",
                    ),
                )
                if (!action.continueOnError) {
                    logger.warn(
                        TAG, "execution aborted at action $index",
                        null,
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                    )
                    aborted = true
                    break
                }
                continue
            }

            logger.info(
                TAG, "action start",
                "flowId" to context.flow.id,
                "execId" to context.executionId,
                "actionIndex" to index,
                "actionClass" to action::class.java.simpleName,
            )

            val actionStartMillis = clock.nowMillis()
            val result: Outcome<Unit, ExecutionError> = try {
                @Suppress("UNCHECKED_CAST")
                val typed = executor as ActionExecutor<Action>
                withTimeoutOrNull(EXECUTION_TIMEOUT_MILLIS) {
                    typed.execute(action, context)
                } ?: Outcome.err(ExecutionError.Timeout(EXECUTION_TIMEOUT_MILLIS))
            } catch (t: Throwable) {
                if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) {
                    throw t
                }
                logger.error(
                    TAG, "action threw",
                    t,
                    "flowId" to context.flow.id,
                    "execId" to context.executionId,
                    "actionIndex" to index,
                )
                Outcome.err(ExecutionError.SystemFailure(t.message ?: t.javaClass.simpleName))
            }
            val actionDurationMs = (clock.nowMillis() - actionStartMillis).coerceAtLeast(0L)

            when (result) {
                is Outcome.Ok -> {
                    okCount++
                    recorder?.append(
                        FlowRunActionResult(
                            index = index,
                            actionClass = action::class.java.simpleName,
                            label = action.label,
                            status = "ok",
                            durationMs = actionDurationMs,
                            errorMessage = null,
                        ),
                    )
                    logger.info(
                        TAG, "action ok",
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "actionIndex" to index,
                    )
                }
                is Outcome.Err -> {
                    errCount++
                    val status = if (result.error is ExecutionError.Timeout) "timeout" else "err"
                    recorder?.append(
                        FlowRunActionResult(
                            index = index,
                            actionClass = action::class.java.simpleName,
                            label = action.label,
                            status = status,
                            durationMs = actionDurationMs,
                            errorMessage = result.error.toString().take(200),
                        ),
                    )
                    logger.warn(
                        TAG, "action err",
                        null,
                        "flowId" to context.flow.id,
                        "execId" to context.executionId,
                        "actionIndex" to index,
                        "error" to result.error,
                    )
                    if (!action.continueOnError) {
                        logger.warn(
                            TAG, "execution aborted at action $index",
                            null,
                            "flowId" to context.flow.id,
                            "execId" to context.executionId,
                        )
                        aborted = true
                        break
                    }
                }
            }
        }
        return ActionRunner.RunStats(okCount = okCount, errCount = errCount, aborted = aborted)
    }

    /** Populate the variable map used by MagicText. Documented binding names live in [MagicTextBindings]. */
    private fun buildVariables(
        flow: Flow,
        event: TriggerEvent,
        execId: String,
    ): MutableMap<String, String> {
        val v = HashMap<String, String>(48)
        v[MagicTextBindings.FLOW_ID] = flow.id
        v[MagicTextBindings.FLOW_NAME] = flow.name
        v[MagicTextBindings.EXEC_ID] = execId
        when (event) {
            is TriggerEvent.NotificationFired -> populateNotifVars(v, event.event)
            is TriggerEvent.ScheduledTime -> populateTimeVars(v, event)
            is TriggerEvent.WebhookReceived -> populateWebhookVars(v, event)
            is TriggerEvent.MqttMessage -> {
                v["mqtt.topic"] = event.topic
                v["mqtt.payload"] = event.payload
                v["mqtt.qos"] = event.qos.toString()
            }
        }
        return v
    }

    private fun populateNotifVars(v: MutableMap<String, String>, event: NotificationEvent) {
        v[MagicTextBindings.NOTIF_PACKAGE] = event.packageName
        v[MagicTextBindings.NOTIF_TITLE] = event.title.orEmpty()
        v[MagicTextBindings.NOTIF_TEXT] = event.text.orEmpty()
        v[MagicTextBindings.NOTIF_BIG_TEXT] = event.bigText.orEmpty()
        v[MagicTextBindings.NOTIF_SUB_TEXT] = event.subText.orEmpty()
        v[MagicTextBindings.NOTIF_CHANNEL] = event.channelId.orEmpty()
        v[MagicTextBindings.NOTIF_ID] = event.notificationId.toString()
        v[MagicTextBindings.NOTIF_KEY] = event.sbnKey
        v[MagicTextBindings.NOTIF_POST_TIME] = event.notificationPostTimeMillis.toString()
        v[MagicTextBindings.NOTIF_GROUP_KEY] = event.groupKey.orEmpty()
        v[MagicTextBindings.NOTIF_TICKER] = event.tickerText.orEmpty()
        v[MagicTextBindings.NOTIF_RAW_EXTRAS] = event.rawExtrasJson.orEmpty()
        event.actionLabels.forEachIndexed { i, label ->
            v[MagicTextBindings.notifActionLabel(i)] = label
        }
    }

    private fun populateTimeVars(v: MutableMap<String, String>, event: TriggerEvent.ScheduledTime) {
        v["trigger.firedAtMillis"] = event.firedAtMillis.toString()
        v["trigger.kind"] = event.triggerKind
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = event.firedAtMillis }
        v["trigger.hour"] = cal.get(java.util.Calendar.HOUR_OF_DAY).toString()
        v["trigger.minute"] = cal.get(java.util.Calendar.MINUTE).toString()
        v["trigger.dayOfWeek"] = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "MON"
            java.util.Calendar.TUESDAY -> "TUE"
            java.util.Calendar.WEDNESDAY -> "WED"
            java.util.Calendar.THURSDAY -> "THU"
            java.util.Calendar.FRIDAY -> "FRI"
            java.util.Calendar.SATURDAY -> "SAT"
            else -> "SUN"
        }
    }

    private fun populateWebhookVars(v: MutableMap<String, String>, event: TriggerEvent.WebhookReceived) {
        v["webhook.method"] = event.method
        v["webhook.path"] = event.path
        v["webhook.body"] = event.body
        for ((name, value) in event.headers) {
            // Lower-cased binding name so {webhook.header.cookie} works regardless of incoming case.
            v["webhook.header.${name.lowercase()}"] = value
        }
        for ((name, value) in event.query) {
            v["webhook.query.$name"] = value
        }
    }

    companion object {
        private const val TAG = "Engine"
        const val EXECUTION_TIMEOUT_MILLIS = 30_000L
        /** Debounce window for externally-triggered fires (time, webhook). */
        const val DEBOUNCE_EXTERNAL_MS = 1_000L
    }
}

/**
 * Mutable per-execution accumulator. One instance per top-level [FlowEngineImpl.executeFlow]
 * call; lives in [FlowEngineImpl.recorders] keyed by `executionId` so [FlowEngineImpl.runActions]
 * can append to it, including from re-entrant calls made by If / Loop / TryCatch executors.
 *
 * Not thread-safe by itself — concurrent appends from parallel executions never target the
 * same recorder because each lives under a unique `executionId`.
 */
private class RunRecorder(
    val flowId: String,
    val flowName: String,
    val executionId: String,
    val triggerKind: String,
    val startedAtMillis: Long,
) {
    private val actionResults = mutableListOf<FlowRunActionResult>()

    fun append(result: FlowRunActionResult) {
        actionResults += result
    }

    /**
     * Snapshot the accumulator into an insertable [FlowRun] row. [stats] comes from the engine's
     * top-level [ActionRunner.RunStats] — these are the canonical numbers; the per-action
     * `actionResults` list may include extras from nested sub-flows (intentional: they help the
     * Runs screen explain why an outer action errored).
     */
    fun toFlowRun(endedAtMillis: Long, stats: ActionRunner.RunStats): FlowRun {
        val firstError = actionResults.firstOrNull { it.status != "ok" }?.errorMessage
        return FlowRun(
            id = 0L,
            flowId = flowId,
            flowName = flowName,
            executionId = executionId,
            triggerKind = triggerKind,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            ok = stats.errCount == 0 && !stats.aborted,
            okCount = stats.okCount,
            errCount = stats.errCount,
            aborted = stats.aborted,
            errorMessage = firstError,
            actionResults = actionResults.toList(),
        )
    }
}
