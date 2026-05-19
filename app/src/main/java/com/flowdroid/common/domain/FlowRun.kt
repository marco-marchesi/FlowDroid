package com.flowdroid.common.domain

/**
 * One completed (or aborted-mid-run) execution of a flow, captured by the engine for the
 * "Runs" tab in the editor.
 *
 *  - One row per top-level [com.flowdroid.common.flow.Flow] execution. Nested If / Loop / TryCatch
 *    sub-action results live denormalised in [actionResults]; we don't fan out separate FlowRun
 *    rows per iteration of a Loop body. That keeps the table small and the UI scannable.
 *  - Persisted by [com.flowdroid.common.repo.FlowRunRepository]; engine writes are best-effort
 *    (a persistence failure never aborts a real flow execution).
 *  - Rows older than two days are pruned by `RetentionWorker`, matching the policy for
 *    notifications / logs / health events.
 *
 * @property id              Row id (auto-generated on insert).
 * @property flowId          UUID of the flow this run belongs to.
 * @property flowName        Snapshot of the flow's name AT THE TIME OF EXECUTION — the user may
 *                           later rename the flow; we preserve what the run actually saw.
 * @property executionId     Per-execution short id (8 hex chars) that already appears in
 *                           [com.flowdroid.common.StructuredLogger] tags for cross-referencing.
 * @property triggerKind     Discriminator of [com.flowdroid.common.flow.TriggerEvent] —
 *                           "NotificationFired", "ScheduledTime", "WebhookReceived".
 * @property startedAtMillis Wall-clock millis when the engine began this run.
 * @property endedAtMillis   Wall-clock millis when execution finished (success OR abort).
 * @property ok              True if every top-level action returned [com.flowdroid.common.Outcome.Ok];
 *                           false if any action errored OR the run aborted.
 * @property okCount         Count of top-level actions whose execution returned Ok.
 * @property errCount        Count of top-level actions whose execution returned Err.
 * @property aborted         True if execution short-circuited because a non-`continueOnError`
 *                           action failed (mirrors the engine's [com.flowdroid.common.flow.ActionRunner.RunStats]).
 * @property errorMessage    Short reason string when [ok] is false (e.g. the last action's error
 *                           message); null on full success.
 * @property actionResults   Per-action breakdown — empty if the flow had no actions.
 */
data class FlowRun(
    val id: Long,
    val flowId: String,
    val flowName: String,
    val executionId: String,
    val triggerKind: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val ok: Boolean,
    val okCount: Int,
    val errCount: Int,
    val aborted: Boolean,
    val errorMessage: String?,
    val actionResults: List<FlowRunActionResult>,
) {
    /** Wall-clock duration in millis. Always non-negative; clamped to 0 if clocks slipped backwards. */
    val durationMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0L)
}

/**
 * One slot in [FlowRun.actionResults]. Captures enough to render a per-action timeline in the
 * UI without re-hydrating the original Action — we don't store the Action's full configuration
 * (that lives in [com.flowdroid.common.flow.Flow.actions] and may have changed since the run).
 *
 * @property index        Zero-based position in the flow's action list at execution time.
 * @property actionClass  Kotlin simple name of the Action subtype ("Http", "PostNotification", …).
 * @property label        User-supplied label, if any (the flow editor's per-card Label field).
 * @property status       "ok" | "err" | "missing_executor" | "timeout".
 * @property durationMs   Wall-clock duration spent in this single action.
 * @property errorMessage Reason string when [status] != "ok"; null otherwise.
 */
data class FlowRunActionResult(
    val index: Int,
    val actionClass: String,
    val label: String?,
    val status: String,
    val durationMs: Long,
    val errorMessage: String?,
)
