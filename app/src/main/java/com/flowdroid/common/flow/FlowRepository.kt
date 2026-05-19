package com.flowdroid.common.flow

import com.flowdroid.common.Outcome
import com.flowdroid.common.repo.PersistError
import kotlinx.coroutines.flow.Flow as KFlow

/**
 * Persists [com.flowdroid.common.flow.Flow]s and answers "which flows match a given event?".
 *
 * Phase 1 MVP: in-memory matching driven by Room queries; expected flow count is small (< 100)
 * so a full scan per event is fine. Indexed lookups can be added later.
 */
interface FlowRepository {

    /** Cold flow of all flows (enabled or not), newest-first. UI binds to this. */
    fun observeAll(): KFlow<List<com.flowdroid.common.flow.Flow>>

    suspend fun get(id: String): Outcome<com.flowdroid.common.flow.Flow?, PersistError>

    /** Returns enabled flows whose trigger is a [Trigger.NotificationPosted]. */
    suspend fun enabledNotificationFlows(): Outcome<List<com.flowdroid.common.flow.Flow>, PersistError>

    suspend fun upsert(flow: com.flowdroid.common.flow.Flow): Outcome<Unit, PersistError>

    suspend fun delete(id: String): Outcome<Int, PersistError>

    suspend fun setEnabled(id: String, enabled: Boolean): Outcome<Int, PersistError>

    /**
     * Duplicate the flow with [id]: load, generate a fresh UUID, append " (copy)" to the name,
     * set `enabled = false` so the duplicate doesn't immediately race the original, and upsert.
     *
     * Returns the new flow's id on success. Fails with `PersistError.ReadFailed` if the source
     * flow doesn't exist or can't be deserialised.
     */
    suspend fun duplicate(id: String, nowMillis: Long): Outcome<String, PersistError>
}
