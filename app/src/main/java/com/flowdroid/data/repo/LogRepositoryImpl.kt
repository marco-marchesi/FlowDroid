package com.flowdroid.data.repo

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.onErr
import com.flowdroid.common.outcomeCatching
import com.flowdroid.common.repo.LogRepository
import com.flowdroid.common.repo.PersistError
import com.flowdroid.data.db.LogEntryDao
import com.flowdroid.data.db.toDomain
import com.flowdroid.data.db.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [LogRepository].
 *
 * Mirrors the structure of [NotificationRepositoryImpl]: every DAO call wrapped in
 * [outcomeCatching], failures logged via [StructuredLogger] with structured fields.
 *
 * Note on logging-the-logger: `LogRepositoryImpl` is itself the persistence sink for the log
 * pipeline. If `dao.insert` fails, calling `logger.warn` here will route through Timber, which
 * itself may try to flush back into this very repository (if the Room tree is plugged in). To
 * keep that loop bounded the [StructuredLogger] contract requires implementations to be
 * failure-tolerant; we trust that contract.
 */
@Singleton
class LogRepositoryImpl @Inject constructor(
    private val dao: LogEntryDao,
    private val logger: StructuredLogger,
    @Suppress("unused") private val clock: Clock,
) : LogRepository {

    private val tag: String = "LogRepo"

    override suspend fun insert(entry: LogEntry): Outcome<Long, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.insert(entry.toEntity())
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "insert failed",
                cause,
                "op" to "insert",
                "level" to entry.level.name,
                "tag" to entry.tag,
                "error" to error,
            )
        }

    override suspend fun insertAll(entries: List<LogEntry>): Outcome<Int, PersistError> =
        outcomeCatching(::mapPersistError) {
            // Empty list shortcut: avoid hitting Room with a no-op transaction.
            if (entries.isEmpty()) {
                0
            } else {
                dao.insertAll(entries.map { it.toEntity() }).size
            }
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "insertAll failed",
                cause,
                "op" to "insertAll",
                "batchSize" to entries.size,
                "error" to error,
            )
        }

    override fun observeRecent(limit: Int, minLevel: LogEntry.Level): Flow<List<LogEntry>> =
        dao.observeRecent(limit = limit, minPriority = minLevel.priority)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun snapshotRecent(count: Int): Outcome<List<LogEntry>, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.snapshotRecent(count).map { it.toDomain() }
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "snapshotRecent failed",
                cause,
                "op" to "snapshotRecent",
                "count" to count,
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
