package com.flowdroid.data.repo

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.onErr
import com.flowdroid.common.outcomeCatching
import com.flowdroid.common.repo.NotificationRepository
import com.flowdroid.common.repo.PersistError
import com.flowdroid.data.db.NotificationEventDao
import com.flowdroid.data.db.toDomain
import com.flowdroid.data.db.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [NotificationRepository].
 *
 * All DAO calls are funnelled through [outcomeCatching] with [mapPersistError]. Every error path
 * is logged via [StructuredLogger] at `WARN` with a structured field naming the operation, so
 * production triage can grep for `tag=NotifRepo op=insert` and find the cause.
 *
 * [Clock] is injected even though this repository doesn't currently read time — see
 * [pruneOlderThan]. Callers pass an absolute cutoff to keep the API time-source-agnostic;
 * the Clock dependency is kept so future helpers (e.g. `pruneOlderThan(daysAgo: Int)`) can
 * compute cutoffs deterministically without re-introducing direct calls to
 * `System.currentTimeMillis`.
 *
 * Failure modes (for callers):
 *  - [PersistError.WriteFailed]   — generic SQLite or unknown error during a write.
 *  - [PersistError.ReadFailed]    — currently unused by this implementation; reads map to
 *                                    `WriteFailed` for SQLite errors. The interface contract
 *                                    leaves this distinction soft.
 *  - [PersistError.DatabaseUnavailable] — DB closed or IO failure.
 *  - [PersistError.Corrupted]     — SQLite reports corruption; needs human attention.
 */
@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val dao: NotificationEventDao,
    private val logger: StructuredLogger,
    @Suppress("unused") private val clock: Clock,
) : NotificationRepository {

    private val tag: String = "NotifRepo"

    override suspend fun insert(event: NotificationEvent): Outcome<Long, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.insert(event.toEntity())
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "insert failed",
                cause,
                "op" to "insert",
                "package" to event.packageName,
                "sbnKey" to event.sbnKey,
                "error" to error,
            )
        }

    override fun observeRecent(limit: Int): Flow<List<NotificationEvent>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun countSince(sinceMillis: Long): Outcome<Int, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.countSince(sinceMillis)
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "countSince failed",
                cause,
                "op" to "countSince",
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
