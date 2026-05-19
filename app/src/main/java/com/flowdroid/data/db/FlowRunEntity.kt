package com.flowdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowdroid.common.domain.FlowRun
import com.flowdroid.common.domain.FlowRunActionResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Room storage shape for [FlowRun].
 *
 * Action results are denormalised into a JSON column (`actionResultsJson`) rather than a child
 * table. Rationale:
 *  - We only ever query "give me run X and all of its actions" — no aggregate query across
 *    individual action rows. Storing the breakdown as a single JSON value avoids a join on every
 *    read.
 *  - Schema evolution is cheaper — adding a new field to `FlowRunActionResult` is a serializer
 *    change with `ignoreUnknownKeys = true` rather than a Room migration.
 *  - Action lists are bounded (a few dozen at most), so the JSON payload stays small.
 *
 * Indexed on `startedAtMillis` for newest-first queries and pruning, and on `flowId` for the
 * per-flow "Runs" tab.
 */
@Entity(
    tableName = "flow_runs",
    indices = [
        Index(value = ["startedAtMillis"], name = "idx_flow_run_started"),
        Index(value = ["flowId"], name = "idx_flow_run_flowId"),
    ],
)
data class FlowRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long,

    @ColumnInfo(name = "flowId")
    val flowId: String,

    @ColumnInfo(name = "flowName")
    val flowName: String,

    @ColumnInfo(name = "executionId")
    val executionId: String,

    @ColumnInfo(name = "triggerKind")
    val triggerKind: String,

    @ColumnInfo(name = "startedAtMillis")
    val startedAtMillis: Long,

    @ColumnInfo(name = "endedAtMillis")
    val endedAtMillis: Long,

    @ColumnInfo(name = "ok")
    val ok: Boolean,

    @ColumnInfo(name = "okCount")
    val okCount: Int,

    @ColumnInfo(name = "errCount")
    val errCount: Int,

    @ColumnInfo(name = "aborted")
    val aborted: Boolean,

    @ColumnInfo(name = "errorMessage")
    val errorMessage: String?,

    @ColumnInfo(name = "actionResultsJson")
    val actionResultsJson: String,
)

/**
 * JSON shape for one action result, persisted as part of [FlowRunEntity.actionResultsJson].
 * Defaults on every field keep forward-compat — an older app reading a newer JSON ignores
 * unknown keys (Room serializer config).
 */
@Serializable
internal data class FlowRunActionResultSurrogate(
    val index: Int = 0,
    val actionClass: String = "?",
    val label: String? = null,
    val status: String = "ok",
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
)

internal object FlowRunJson {
    val json: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
    private val listSerializer = ListSerializer(FlowRunActionResultSurrogate.serializer())

    fun encode(results: List<FlowRunActionResult>): String =
        json.encodeToString(
            listSerializer,
            results.map {
                FlowRunActionResultSurrogate(
                    index = it.index,
                    actionClass = it.actionClass,
                    label = it.label,
                    status = it.status,
                    durationMs = it.durationMs,
                    errorMessage = it.errorMessage,
                )
            },
        )

    fun decode(jsonText: String): List<FlowRunActionResult> =
        try {
            json.decodeFromString(listSerializer, jsonText).map {
                FlowRunActionResult(
                    index = it.index,
                    actionClass = it.actionClass,
                    label = it.label,
                    status = it.status,
                    durationMs = it.durationMs,
                    errorMessage = it.errorMessage,
                )
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            // Tolerate corrupt JSON — better to lose the per-action breakdown than the whole row.
            emptyList()
        }
}

fun FlowRunEntity.toDomain(): FlowRun = FlowRun(
    id = id,
    flowId = flowId,
    flowName = flowName,
    executionId = executionId,
    triggerKind = triggerKind,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    ok = ok,
    okCount = okCount,
    errCount = errCount,
    aborted = aborted,
    errorMessage = errorMessage,
    actionResults = FlowRunJson.decode(actionResultsJson),
)

fun FlowRun.toEntity(): FlowRunEntity = FlowRunEntity(
    id = id,
    flowId = flowId,
    flowName = flowName,
    executionId = executionId,
    triggerKind = triggerKind,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    ok = ok,
    okCount = okCount,
    errCount = errCount,
    aborted = aborted,
    errorMessage = errorMessage,
    actionResultsJson = FlowRunJson.encode(actionResults),
)
