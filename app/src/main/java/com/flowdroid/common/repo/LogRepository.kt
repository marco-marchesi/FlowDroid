package com.flowdroid.common.repo

import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.domain.LogEntry
import kotlinx.coroutines.flow.Flow

interface LogRepository {

    suspend fun insert(entry: LogEntry): Outcome<Long, PersistError>

    /** Batched insert for the buffered log tree. */
    suspend fun insertAll(entries: List<LogEntry>): Outcome<Int, PersistError>

    /** Cold flow of recent log entries newest-first, filtered by minimum level. */
    fun observeRecent(limit: Int = 500, minLevel: LogEntry.Level = LogEntry.Level.DEBUG): Flow<List<LogEntry>>

    /** Snapshot of the last [count] entries — used by the crash handler. */
    suspend fun snapshotRecent(count: Int): Outcome<List<LogEntry>, PersistError>

    suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError>
}

interface HealthRepository {

    suspend fun insert(event: HealthEvent): Outcome<Long, PersistError>

    fun observeRecent(limit: Int = 200): Flow<List<HealthEvent>>

    /** Most recent event of a given kind, or null. */
    suspend fun mostRecent(kind: HealthEvent.Kind): Outcome<HealthEvent?, PersistError>

    /** Count events of given kinds in a time window. */
    suspend fun countSince(kinds: Set<HealthEvent.Kind>, sinceMillis: Long): Outcome<Int, PersistError>

    suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError>
}
