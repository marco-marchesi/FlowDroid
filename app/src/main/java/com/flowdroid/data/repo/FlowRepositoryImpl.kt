package com.flowdroid.data.repo

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.onErr
import com.flowdroid.common.onOk
import com.flowdroid.common.outcomeCatching
import com.flowdroid.common.repo.PersistError
import com.flowdroid.data.db.FlowDao
import com.flowdroid.data.db.FlowEntity
import com.flowdroid.data.db.toDomain
import com.flowdroid.data.db.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow as KFlow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [FlowRepository].
 *
 * Failure handling follows the established pattern in [NotificationRepositoryImpl]:
 *  - Every DAO call is wrapped in [outcomeCatching] with [mapPersistError] so SQLite / Room
 *    exceptions surface as typed [PersistError] cases.
 *  - Every error path is logged at WARN with structured fields. Every success path is logged at
 *    INFO with the `flowId`.
 *
 * Deserialisation resilience:
 *  - [observeAll] and [enabledNotificationFlows] decode JSON blobs. A single corrupt blob must
 *    not break the entire stream. Each row is decoded in a try/catch; failures are logged WARN
 *    and the row is omitted from the result. Compare to: any other strategy (throwing, returning
 *    `Outcome.Err` for the whole list) would make a single malformed row a denial-of-service.
 *
 * `createdAt` policy:
 *  - On upsert, if the incoming flow has `createdAt == 0L` we treat that as "new" and stamp it
 *    with `clock.nowMillis()`. Otherwise we preserve whatever the caller passed in. This matches
 *    the contract documented on [Flow.createdAt] ("wall-clock millis of creation").
 *  - `updatedAt` is always set to `clock.nowMillis()` on upsert.
 */
@Singleton
class FlowRepositoryImpl @Inject constructor(
    private val dao: FlowDao,
    private val clock: Clock,
    private val logger: StructuredLogger,
) : FlowRepository {

    private val tag: String = "FlowRepo"

    override fun observeAll(): KFlow<List<Flow>> =
        dao.observeAll().map { rows -> rows.mapNotNull { decodeOrSkip(it, op = "observeAll") } }

    override suspend fun get(id: String): Outcome<Flow?, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.get(id)?.let { decodeOrSkip(it, op = "get") }
        }.onOk {
            logger.info(tag, "get ok", "op" to "get", "flowId" to id, "found" to (it != null))
        }.onErr { error, cause ->
            logger.warn(tag, "get failed", cause, "op" to "get", "flowId" to id, "error" to error)
        }

    override suspend fun enabledNotificationFlows(): Outcome<List<Flow>, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.enabled()
                .mapNotNull { decodeOrSkip(it, op = "enabledNotificationFlows") }
                .filter { flow -> flow.triggers.any { it is Trigger.NotificationPosted } }
        }.onOk { flows ->
            logger.info(
                tag,
                "enabledNotificationFlows ok",
                "op" to "enabledNotificationFlows",
                "count" to flows.size,
            )
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "enabledNotificationFlows failed",
                cause,
                "op" to "enabledNotificationFlows",
                "error" to error,
            )
        }

    override suspend fun upsert(flow: Flow): Outcome<Unit, PersistError> {
        val now = clock.nowMillis()
        val stamped = flow.copy(
            createdAt = if (flow.createdAt == 0L) now else flow.createdAt,
            updatedAt = now,
        )
        return outcomeCatching(::mapPersistError) {
            dao.upsert(stamped.toEntity())
        }.onOk {
            logger.info(
                tag,
                "upsert ok",
                "op" to "upsert",
                "flowId" to stamped.id,
                "enabled" to stamped.enabled,
            )
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "upsert failed",
                cause,
                "op" to "upsert",
                "flowId" to stamped.id,
                "error" to error,
            )
        }
    }

    override suspend fun delete(id: String): Outcome<Int, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.delete(id)
        }.onOk { affected ->
            logger.info(
                tag,
                "delete ok",
                "op" to "delete",
                "flowId" to id,
                "affected" to affected,
            )
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "delete failed",
                cause,
                "op" to "delete",
                "flowId" to id,
                "error" to error,
            )
        }

    override suspend fun setEnabled(id: String, enabled: Boolean): Outcome<Int, PersistError> =
        outcomeCatching(::mapPersistError) {
            dao.setEnabled(id, enabled, clock.nowMillis())
        }.onOk { affected ->
            logger.info(
                tag,
                "setEnabled ok",
                "op" to "setEnabled",
                "flowId" to id,
                "enabled" to enabled,
                "affected" to affected,
            )
        }.onErr { error, cause ->
            logger.warn(
                tag,
                "setEnabled failed",
                cause,
                "op" to "setEnabled",
                "flowId" to id,
                "enabled" to enabled,
                "error" to error,
            )
        }

    override suspend fun duplicate(id: String, nowMillis: Long): Outcome<String, PersistError> {
        val source = when (val r = get(id)) {
            is Outcome.Ok -> r.value
                ?: return Outcome.err(PersistError.ReadFailed("flow $id not found"))
            is Outcome.Err -> return Outcome.err(r.error, r.cause)
        }
        val newId = java.util.UUID.randomUUID().toString()
        val copy = source.copy(
            id = newId,
            name = if (source.name.isBlank()) "(copy)" else "${source.name} (copy)",
            enabled = false,
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        return when (val r = upsert(copy)) {
            is Outcome.Ok -> {
                logger.info(tag, "duplicate ok",
                    "op" to "duplicate", "sourceId" to id, "newId" to newId)
                Outcome.ok(newId)
            }
            is Outcome.Err -> {
                logger.warn(tag, "duplicate failed",
                    r.cause, "op" to "duplicate", "sourceId" to id, "error" to r.error)
                Outcome.err(r.error, r.cause)
            }
        }
    }

    /**
     * Decode a [FlowEntity] to [Flow], logging and returning null on failure.
     *
     * One malformed row must not break the entire feed; the engine and UI receive the rest. A
     * future "show corrupted flows in UI" feature would replace this with a typed
     * `DecodedRow` sum, but Phase 1 just drops them.
     */
    private fun decodeOrSkip(entity: FlowEntity, op: String): Flow? = try {
        entity.toDomain()
    } catch (t: Throwable) {
        if (t is OutOfMemoryError ||
            t is kotlin.coroutines.cancellation.CancellationException
        ) throw t
        logger.warn(
            tag,
            "decode failed; skipping flow",
            t,
            "op" to op,
            "flowId" to entity.id,
            "error" to t.javaClass.simpleName,
        )
        null
    }
}
