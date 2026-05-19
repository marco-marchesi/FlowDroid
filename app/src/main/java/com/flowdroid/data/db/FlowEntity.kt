package com.flowdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowdroid.common.flow.Flow

/**
 * Room storage shape for [com.flowdroid.common.flow.Flow].
 *
 * The [com.flowdroid.common.flow.Trigger] and [com.flowdroid.common.flow.Action] hierarchies
 * are persisted as JSON blobs ([triggersJson] / [actionsJson]). This keeps the schema flat (no
 * extra tables per subtype) and trivially extensible — new subtypes only require a serialiser
 * update, not a Room migration.
 *
 * Trade-offs:
 *  - Pros: zero migration cost when adding new Trigger / Action kinds; no per-subtype joins;
 *    the JSON is human-inspectable in debug tools.
 *  - Cons: we cannot index inside the JSON. For Phase 1 (< 100 flows) full scan + in-memory
 *    filter is acceptable. If flow count grows beyond ~thousands we'd promote
 *    `triggerKind` to its own indexed column.
 *
 * Index on `enabled` keeps the dominant query — "give me all enabled flows" — O(matching rows)
 * rather than O(total flows).
 */
@Entity(
    tableName = "flows",
    indices = [
        Index(value = ["enabled"], name = "idx_flow_enabled"),
    ],
)
data class FlowEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean,

    /**
     * JSON-encoded `List<com.flowdroid.common.flow.Trigger>` (JSON array of objects, each with a
     * `"type"` discriminator). Decoded via [deserializeTriggers]. Malformed values cause the
     * repository to log a WARN and skip the flow — never to throw out of the public API.
     *
     * Phase 1 stored a single trigger object here; Phase 2 (DB v3) widens this to an array via
     * [com.flowdroid.data.db.FlowDroidDatabase.MIGRATION_2_3], which wraps the old object as a
     * one-element array in place.
     */
    @ColumnInfo(name = "triggersJson")
    val triggersJson: String,

    /**
     * JSON-encoded `List<com.flowdroid.common.flow.Action>` (JSON array of objects, each with a
     * `"type"` discriminator). Decoded via [deserializeActions].
     */
    @ColumnInfo(name = "actionsJson")
    val actionsJson: String,

    @ColumnInfo(name = "notes")
    val notes: String?,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
)

/**
 * Decode a [FlowEntity] into the domain [Flow].
 *
 * Throws [kotlinx.serialization.SerializationException] if [triggersJson] / [actionsJson] is
 * malformed or carries an unknown subtype discriminator. The repository layer catches this and
 * surfaces it as a WARN log entry rather than failing the entire observe stream.
 */
internal fun FlowEntity.toDomain(): Flow = Flow(
    id = id,
    name = name,
    enabled = enabled,
    triggers = deserializeTriggers(triggersJson),
    actions = deserializeActions(actionsJson),
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * Encode a domain [Flow] into a [FlowEntity] ready for `upsert`.
 */
internal fun Flow.toEntity(): FlowEntity = FlowEntity(
    id = id,
    name = name,
    enabled = enabled,
    triggersJson = serializeTriggers(triggers),
    actionsJson = serializeActions(actions),
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
