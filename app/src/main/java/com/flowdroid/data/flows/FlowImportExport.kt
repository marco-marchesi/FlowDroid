package com.flowdroid.data.flows

import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.flow.Flow
import com.flowdroid.data.db.ActionSurrogate
import com.flowdroid.data.db.FlowSerialization
import com.flowdroid.data.db.TriggerSurrogate
import com.flowdroid.data.db.toDomain
import com.flowdroid.data.db.toSurrogate
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * JSON import/export for [Flow]s, intended for backup, sharing, and version-control. Lives in the
 * `data` layer because the on-disk shape reuses the same surrogate classes that drive Room storage —
 * any new trigger/action that round-trips through the DB automatically round-trips through
 * import/export without further changes.
 *
 * Wire format
 * -----------
 * A single flow:
 * ```json
 * { "version": 1, "flow": { "name": "...", "enabled": true, "triggers": [...], "actions": [...], "notes": null } }
 * ```
 *
 * Many flows:
 * ```json
 * { "version": 1, "flows": [ { ... }, { ... } ] }
 * ```
 *
 * The wrapper version is a single integer so future format changes can be detected and migrated.
 * Today only `version == 1` is accepted; older or newer payloads fail with [ImportError.UnsupportedVersion].
 *
 * Import side-effects
 * -------------------
 * Imported flows always:
 *  - Receive a fresh [UUID] — re-importing your own export is therefore non-destructive.
 *  - Start `enabled = false` so they don't race existing flows the moment they land.
 *  - Get fresh `createdAt` / `updatedAt` from the supplied [Clock].
 *
 * The caller is responsible for actually persisting the returned flows (e.g. via
 * `FlowRepository.upsert`).
 */
object FlowImportExport {

    const val CURRENT_VERSION: Int = 1

    /** Encode a single [Flow] as a `version 1` single-flow JSON document. */
    fun exportFlow(flow: Flow): String {
        val payload = SingleFlowExport(
            version = CURRENT_VERSION,
            flow = flow.toSurrogate(),
        )
        return FlowSerialization.json.encodeToString(SingleFlowExport.serializer(), payload)
    }

    /** Encode an ordered list of [Flow]s as a `version 1` multi-flow JSON document. */
    fun exportFlows(flows: List<Flow>): String {
        val payload = MultiFlowExport(
            version = CURRENT_VERSION,
            flows = flows.map { it.toSurrogate() },
        )
        return FlowSerialization.json.encodeToString(MultiFlowExport.serializer(), payload)
    }

    /**
     * Decode either shape produced by [exportFlow] / [exportFlows].
     *
     * Returns:
     *  - [Outcome.Ok] holding the parsed flows (always at least one, with fresh ids and
     *    `enabled = false`).
     *  - [Outcome.Err] holding a typed [ImportError] explaining what failed.
     */
    fun importFlows(json: String, clock: Clock): Outcome<List<Flow>, ImportError> {
        if (json.isBlank()) return Outcome.err(ImportError.MalformedJson("empty document"))

        val root: JsonObject = try {
            FlowSerialization.json.parseToJsonElement(json) as? JsonObject
                ?: return Outcome.err(ImportError.MalformedJson("top-level element is not a JSON object"))
        } catch (e: SerializationException) {
            return Outcome.err(ImportError.MalformedJson(e.message ?: "parse error"))
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization wraps some lex errors in IAE.
            return Outcome.err(ImportError.MalformedJson(e.message ?: "parse error"))
        }

        val version = root["version"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return Outcome.err(ImportError.MalformedJson("missing or non-integer \"version\""))
        if (version != CURRENT_VERSION) {
            return Outcome.err(ImportError.UnsupportedVersion(version))
        }

        val surrogates: List<FlowSurrogate> = try {
            when {
                root.containsKey("flows") -> {
                    val arr = root.getValue("flows").jsonArray
                    arr.map {
                        FlowSerialization.json.decodeFromJsonElement(
                            FlowSurrogate.serializer(), it,
                        )
                    }
                }
                root.containsKey("flow") -> listOf(
                    FlowSerialization.json.decodeFromJsonElement(
                        FlowSurrogate.serializer(), root.getValue("flow"),
                    ),
                )
                else -> return Outcome.err(
                    ImportError.MalformedJson("payload has neither \"flow\" nor \"flows\""),
                )
            }
        } catch (e: SerializationException) {
            return Outcome.err(ImportError.MalformedJson(e.message ?: "shape error"))
        } catch (e: IllegalArgumentException) {
            return Outcome.err(ImportError.MalformedJson(e.message ?: "shape error"))
        }

        if (surrogates.isEmpty()) return Outcome.err(ImportError.Empty)

        val now = clock.nowMillis()
        val flows = surrogates.map { s ->
            Flow(
                id = UUID.randomUUID().toString(),
                name = s.name,
                enabled = false,
                triggers = s.triggers.map { it.toDomain() },
                actions = s.actions.map { it.toDomain() },
                notes = s.notes,
                createdAt = now,
                updatedAt = now,
            )
        }
        return Outcome.ok(flows)
    }

    private fun Flow.toSurrogate(): FlowSurrogate = FlowSurrogate(
        name = name,
        enabled = enabled,
        triggers = triggers.map { it.toSurrogate() },
        actions = actions.map { it.toSurrogate() },
        notes = notes,
    )
}

/** Typed reasons a [FlowImportExport.importFlows] call may fail. */
sealed interface ImportError {
    /** The bytes were not parseable JSON, or the shape was wrong. */
    data class MalformedJson(val reason: String) : ImportError
    /** The payload was readable but tagged with a version this build does not understand. */
    data class UnsupportedVersion(val version: Int) : ImportError
    /** The payload was valid and on-version but contained zero flows. */
    data object Empty : ImportError
}

/**
 * Serializable mirror of [com.flowdroid.common.flow.Flow] — exported subset only. Excludes:
 *  - `id` (regenerated on import to avoid collisions)
 *  - `createdAt` / `updatedAt` (regenerated to match the importing device's clock)
 */
@Serializable
internal data class FlowSurrogate(
    val name: String,
    val enabled: Boolean,
    val triggers: List<TriggerSurrogate>,
    val actions: List<ActionSurrogate>,
    val notes: String? = null,
)

@Serializable
internal data class SingleFlowExport(
    val version: Int,
    val flow: FlowSurrogate,
)

@Serializable
internal data class MultiFlowExport(
    val version: Int,
    val flows: List<FlowSurrogate>,
)
