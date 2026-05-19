package com.flowdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [NotificationEventEntity].
 *
 * Insert uses [OnConflictStrategy.ABORT] — conflicts indicate a programmer error (duplicate primary
 * key) and we want them surfaced so the repository can log and the test suite can flag them.
 *
 * The reactive [observeRecent] returns a Room-managed [Flow]; Room emits a fresh list every time
 * any insert/update/delete touches `notification_events`. The cancellation contract is the standard
 * Flow one — collectors should be tied to a lifecycle scope.
 */
@Dao
interface NotificationEventDao {

    /**
     * Insert a single event. Returns the assigned rowid.
     *
     * Throws [android.database.sqlite.SQLiteConstraintException] on conflict (e.g. duplicate
     * primary key). The repository layer translates this to [com.flowdroid.common.repo.PersistError].
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: NotificationEventEntity): Long

    /**
     * Cold flow of the most recent events, newest-first. Emits a new list every time the underlying
     * table changes.
     */
    @Query(
        """
        SELECT * FROM notification_events
        ORDER BY postTimeMillis DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<NotificationEventEntity>>

    /**
     * Number of events with `postTimeMillis >= sinceMillis`. Used by the rate-limit / debounce
     * subsystem.
     */
    @Query(
        """
        SELECT COUNT(*) FROM notification_events
        WHERE postTimeMillis >= :sinceMillis
        """,
    )
    suspend fun countSince(sinceMillis: Long): Int

    /**
     * Delete events with `postTimeMillis < olderThanMillis`. Returns the number of rows removed
     * so the caller can log how much it pruned. Caller decides cadence; this method is a no-op
     * safety net intended to be invoked from a periodic [androidx.work.WorkManager] job.
     */
    @Query("DELETE FROM notification_events WHERE postTimeMillis < :olderThanMillis")
    suspend fun pruneOlderThan(olderThanMillis: Long): Int
}
