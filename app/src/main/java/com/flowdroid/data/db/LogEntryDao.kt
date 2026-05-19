package com.flowdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [LogEntryEntity].
 *
 * Inserts conflict with [OnConflictStrategy.ABORT] — same rationale as [NotificationEventDao].
 * Batch inserts ([insertAll]) are provided for the buffered log Tree which flushes log entries
 * in groups for throughput (target: 1000 entries < 500 ms — see `AC-PERF-004`).
 *
 * The level filter on [observeRecent] is implemented as an integer comparison on
 * [LogEntryEntity.levelPriority] — see entity docs.
 */
@Dao
interface LogEntryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: LogEntryEntity): Long

    /**
     * Insert many entries in one transaction. Returns the assigned rowids in the same order as
     * [entities] for any caller that needs them; the repository typically only forwards the size.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<LogEntryEntity>): List<Long>

    /**
     * Newest-first cold flow with a minimum priority filter. Use [LogEntry.Level.priority] as the
     * `minPriority` argument — repositories handle this translation.
     */
    @Query(
        """
        SELECT * FROM log_entries
        WHERE levelPriority >= :minPriority
        ORDER BY timestampMillis DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int, minPriority: Int): Flow<List<LogEntryEntity>>

    /**
     * Snapshot the latest [count] entries — used by the crash handler so the report includes
     * recent log context. Does not subscribe to changes; one-shot read.
     */
    @Query(
        """
        SELECT * FROM log_entries
        ORDER BY timestampMillis DESC
        LIMIT :count
        """,
    )
    suspend fun snapshotRecent(count: Int): List<LogEntryEntity>

    @Query("DELETE FROM log_entries WHERE timestampMillis < :olderThanMillis")
    suspend fun pruneOlderThan(olderThanMillis: Long): Int
}
