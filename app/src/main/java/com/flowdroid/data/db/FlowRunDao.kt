package com.flowdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [FlowRunEntity]. Single-table queries — no joins. All reads are newest-first.
 */
@Dao
interface FlowRunDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: FlowRunEntity): Long

    @Query(
        """
        SELECT * FROM flow_runs
        WHERE flowId = :flowId
        ORDER BY startedAtMillis DESC
        LIMIT :limit
        """,
    )
    fun observeRecentForFlow(flowId: String, limit: Int): Flow<List<FlowRunEntity>>

    @Query(
        """
        SELECT * FROM flow_runs
        ORDER BY startedAtMillis DESC
        LIMIT :limit
        """,
    )
    fun observeRecentAll(limit: Int): Flow<List<FlowRunEntity>>

    @Query("DELETE FROM flow_runs WHERE startedAtMillis < :olderThanMillis")
    suspend fun pruneOlderThan(olderThanMillis: Long): Int
}
