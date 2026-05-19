package com.flowdroid.data.repo

import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.FlowRun
import com.flowdroid.common.onErr
import com.flowdroid.common.outcomeCatching
import com.flowdroid.common.repo.FlowRunRepository
import com.flowdroid.common.repo.PersistError
import com.flowdroid.data.db.FlowRunDao
import com.flowdroid.data.db.toDomain
import com.flowdroid.data.db.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [FlowRunRepository]. Same Outcome-wrapping pattern as the other repositories:
 * every DAO call goes through [outcomeCatching] with [mapPersistError]; failures are logged and
 * returned as typed errors, never thrown.
 */
@Singleton
class FlowRunRepositoryImpl @Inject constructor(
    private val dao: FlowRunDao,
    private val logger: StructuredLogger,
) : FlowRunRepository {

    private val tag: String = "FlowRunRepo"

    override suspend fun insert(run: FlowRun): Outcome<Long, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.insert(run.toEntity())
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "insert failed",
                cause,
                "op" to "insert",
                "flowId" to run.flowId,
                "execId" to run.executionId,
                "error" to error,
            )
        }

    override fun observeRecentForFlow(flowId: String, limit: Int): Flow<List<FlowRun>> =
        dao.observeRecentForFlow(flowId, limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecentAll(limit: Int): Flow<List<FlowRun>> =
        dao.observeRecentAll(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.pruneOlderThan(olderThanMillis)
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "pruneOlderThan failed",
                cause,
                "op" to "pruneOlderThan",
                "olderThanMillis" to olderThanMillis,
                "error" to error,
            )
        }
}

/** No-op fallback used by tests and any code path that wants to disable run persistence. */
object NoOpFlowRunRepository : FlowRunRepository {
    override suspend fun insert(run: FlowRun): Outcome<Long, PersistError> = Outcome.ok(0L)
    override fun observeRecentForFlow(flowId: String, limit: Int): Flow<List<FlowRun>> =
        kotlinx.coroutines.flow.emptyFlow()
    override fun observeRecentAll(limit: Int): Flow<List<FlowRun>> =
        kotlinx.coroutines.flow.emptyFlow()
    override suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError> =
        Outcome.ok(0)
}
