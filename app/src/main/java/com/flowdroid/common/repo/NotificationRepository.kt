package com.flowdroid.common.repo

import com.flowdroid.common.Outcome
import com.flowdroid.common.domain.NotificationEvent
import kotlinx.coroutines.flow.Flow

/**
 * Persists and queries [NotificationEvent]s observed by the listener.
 *
 * Implementations MUST:
 *  - return `Outcome.Err` for any expected persistence failure; never let SQLException bubble.
 *  - use [com.flowdroid.common.FlowDroidException.Persistence] only for invariant violations.
 *  - keep `observeRecent` cold and backed by Room's Flow support — UI subscribes to it.
 */
interface NotificationRepository {

    /** Insert a freshly-observed event. Returns the assigned rowid. */
    suspend fun insert(event: NotificationEvent): Outcome<Long, PersistError>

    /** Cold flow of the last [limit] events ordered newest-first. */
    fun observeRecent(limit: Int = 200): Flow<List<NotificationEvent>>

    /** Count of events received since [sinceMillis] (wall-clock). */
    suspend fun countSince(sinceMillis: Long): Outcome<Int, PersistError>

    /** Delete entries older than [olderThanMillis] (wall-clock). Returns rows deleted. */
    suspend fun pruneOlderThan(olderThanMillis: Long): Outcome<Int, PersistError>
}

sealed interface PersistError {
    data object DatabaseUnavailable : PersistError
    data class WriteFailed(val reason: String) : PersistError
    data class ReadFailed(val reason: String) : PersistError
    data class Corrupted(val reason: String) : PersistError
}
