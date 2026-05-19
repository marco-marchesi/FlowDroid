package com.flowdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.flowdroid.common.domain.HealthEvent
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [HealthEventEntity].
 *
 * Insert uses [OnConflictStrategy.ABORT]; conflicts surface as exceptions, mapped to
 * [com.flowdroid.common.repo.PersistError] by the repository.
 *
 * The `mostRecent` and `countSince` queries support the Health screen (PLAN §7.5) which shows
 * uptime, last connection time, and missed events.
 */
@Dao
interface HealthEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HealthEventEntity): Long

    @Query(
        """
        SELECT * FROM health_events
        ORDER BY timestampMillis DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<HealthEventEntity>>

    /**
     * The latest event of a single kind, or null if none has been recorded.
     */
    @Query(
        """
        SELECT * FROM health_events
        WHERE kind = :kind
        ORDER BY timestampMillis DESC
        LIMIT 1
        """,
    )
    suspend fun mostRecent(kind: HealthEvent.Kind): HealthEventEntity?

    /**
     * Count events whose `kind` is in [kinds] and `timestampMillis >= sinceMillis`. Used by the
     * watchdog ("how many rebind requests in the last hour?") and the Health screen.
     */
    @Query(
        """
        SELECT COUNT(*) FROM health_events
        WHERE kind IN (:kinds) AND timestampMillis >= :sinceMillis
        """,
    )
    suspend fun countSince(kinds: List<HealthEvent.Kind>, sinceMillis: Long): Int

    @Query("DELETE FROM health_events WHERE timestampMillis < :olderThanMillis")
    suspend fun pruneOlderThan(olderThanMillis: Long): Int
}
