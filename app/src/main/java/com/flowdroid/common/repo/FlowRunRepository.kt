package com.flowdroid.common.repo

import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.FlowRun
import kotlinx.coroutines.flow.Flow

/**
 * Persists and queries [FlowRun]s emitted by the engine.
 *
 * Implementations MUST:
 *  - return `Outcome.Err` for any expected persistence failure; the engine treats writes as
 *    best-effort and a failed insert must never abort a real flow execution.
 *  - keep [observeRecentForFlow] / [observeRecentAll] cold and backed by Room's Flow support so
 *    the UI re-renders automatically as new runs come in.
 */
interface FlowRunRepository {

    /** Insert one completed run. Returns the assigned rowid (1 on auto-increment on first row). */
    suspend fun insert(run: FlowRun): Outcome<Long, PersistError>

    /** Cold flow of the last [limit] runs for a specific flow, newest first. */
    fun observeRecentForFlow(flowId: String, limit: Int = 50): Flow<List<FlowRun>>

    /** Cold flow of the last [limit] runs across every flow, newest first. */
    fun observeRecentAll(limit: Int = 200): Flow<List<FlowRun>>

    /** Delete runs older than [olderThanMillis] (wall-clock). Returns rows deleted. */
    suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError>
}
