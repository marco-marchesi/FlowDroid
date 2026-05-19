package com.flowdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [FlowEntity].
 *
 * Insert uses [OnConflictStrategy.REPLACE] because flows are identified by a stable string `id`
 * (UUID assigned at creation). The repository layer is the only caller; it constructs the entity
 * with the correct `id` so REPLACE behaves as upsert.
 *
 * Reactive [observeAll] returns a Room-managed [Flow]; consumers should tie collection to a
 * lifecycle scope.
 */
@Dao
interface FlowDao {

    /** Cold flow of all flows, newest-updated first. The UI list binds to this. */
    @Query("SELECT * FROM flows ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FlowEntity>>

    /** One-shot lookup by id. Returns null if no row matches. */
    @Query("SELECT * FROM flows WHERE id = :id LIMIT 1")
    suspend fun get(id: String): FlowEntity?

    /**
     * Snapshot of enabled flows. The engine calls this every time it needs to evaluate a new event.
     * Index on `enabled` keeps this O(matching).
     */
    @Query("SELECT * FROM flows WHERE enabled = 1")
    suspend fun enabled(): List<FlowEntity>

    /**
     * Insert-or-replace. Behaves as an upsert because the primary key is the externally-assigned
     * `id`. Returns nothing; success is indicated by the absence of an exception, which the
     * repository's `outcomeCatching` boundary already handles.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FlowEntity)

    /** Delete by id. Returns the number of rows removed (0 or 1). */
    @Query("DELETE FROM flows WHERE id = :id")
    suspend fun delete(id: String): Int

    /**
     * Toggle the enabled flag for a single flow and bump its `updatedAt`. Returns the number of
     * rows affected (0 if no row with that id exists, 1 otherwise).
     */
    @Query("UPDATE flows SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long): Int
}
