package com.flowdroid.common.flow

/**
 * Runs an ordered list of [Action]s under a given [ExecutionContext]. Pulled out as a separate
 * interface so [com.flowdroid.engine.actions.IfExecutor] (and any future control-flow executor
 * like Loop / TryCatch) can recursively run sub-flows without taking a direct dependency on
 * the engine class — which would otherwise create a circular Hilt graph
 * (FlowEngineImpl → IfExecutor → FlowEngineImpl).
 *
 * The single concrete implementation is [com.flowdroid.engine.FlowEngineImpl]. Sub-flow executors
 * inject `dagger.Lazy<ActionRunner>` to break the cycle at construction.
 */
interface ActionRunner {
    /**
     * Execute [actions] sequentially against [context]. Returns aggregate stats so the caller
     * can decide how to surface partial failure to the parent flow. The runner honors each
     * action's `continueOnError`: a failing action without that flag aborts the LIST (not the
     * parent flow).
     */
    suspend fun runActions(actions: List<Action>, context: ExecutionContext): RunStats

    data class RunStats(
        val okCount: Int,
        val errCount: Int,
        /** True if execution short-circuited because a non-`continueOnError` action failed. */
        val aborted: Boolean,
    )
}
