package com.flowdroid.data.repo

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.onErr
import com.flowdroid.common.outcomeCatching
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.PersistError
import com.flowdroid.data.db.HealthEventDao
import com.flowdroid.data.db.toDomain
import com.flowdroid.data.db.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [HealthRepository].
 *
 * Same pattern as the other two repositories: every DAO call is funnelled through
 * [outcomeCatching] with [mapPersistError]; structured warnings on every failure path.
 */
@Singleton
class HealthRepositoryImpl @Inject constructor(
    private val dao: HealthEventDao,
    private val logger: StructuredLogger,
    @Suppress("unused") private val clock: Clock,
) : HealthRepository {

    private val tag: String = "HealthRepo"

    override suspend fun insert(event: HealthEvent): Outcome<Long, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.insert(event.toEntity())
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "insert failed",
                cause,
                "op" to "insert",
                "kind" to event.kind.name,
                "error" to error,
            )
        }

    override fun observeRecent(limit: Int): Flow<List<HealthEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun mostRecent(kind: HealthEvent.Kind): Outcome<HealthEvent?, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.mostRecent(kind)?.toDomain()
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "mostRecent failed",
                cause,
                "op" to "mostRecent",
                "kind" to kind.name,
                "error" to error,
            )
        }

    override suspend fun countSince(
        kinds: Set<HealthEvent.Kind>,
        sinceMillis: Long,
    ): Outcome<Int, PersistError> =
        outcomeCatching(::mapPersistError) {
            // Empty set → trivially zero; avoid an IN () query (invalid SQL) and a useless round-trip.
            if (kinds.isEmpty()) 0 else dao.countSince(kinds.toList(), sinceMillis)
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "countSince failed",
                cause,
                "op" to "countSince",
                "kinds" to kinds.joinToString(",") { it.name },
                "sinceMillis" to sinceMillis,
                "error" to error,
            )
        }

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
