package com.flowdroid.data.db

import androidx.room.TypeConverter
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.domain.LogEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room [TypeConverter]s for non-primitive column types.
 *
 * The data layer stores three "exotic" things:
 *  - `List<String>` — JSON-encoded via [kotlinx.serialization.json.Json].
 *  - [LogEntry.Level] — persisted as the enum's [Enum.name] (stable across renames is *not* guaranteed:
 *    if we rename an enum constant later we must write a migration).
 *  - [HealthEvent.Kind] — same treatment as [LogEntry.Level].
 *
 * Failure modes:
 *  - Decoding an unrecognised enum name throws [IllegalArgumentException]. Room will wrap this and
 *    the repository's `outcomeCatching` boundary maps it to `PersistError.Corrupted`. We deliberately
 *    do NOT swallow unknown enum names: silently dropping a value would hide real data corruption.
 *  - Decoding malformed JSON for `actionLabels` throws — same treatment.
 */
internal object Converters {

    /**
     * Shared [Json] configuration. `ignoreUnknownKeys` is harmless here because we only serialise
     * known shapes; we keep it on as future-proofing in case the schema is extended.
     */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stringListSerializer = ListSerializer(String.serializer())

    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>): String =
        json.encodeToString(stringListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String): List<String> {
        // Treat null/empty as empty list — defensive but cheap. Room itself shouldn't hand us null
        // for a non-null column, but explicit handling keeps tests of edge cases simple.
        if (value.isEmpty()) return emptyList()
        return json.decodeFromString(stringListSerializer, value)
    }

    @TypeConverter
    @JvmStatic
    fun fromLogLevel(level: LogEntry.Level): String = level.name

    @TypeConverter
    @JvmStatic
    fun toLogLevel(name: String): LogEntry.Level = LogEntry.Level.valueOf(name)

    @TypeConverter
    @JvmStatic
    fun fromHealthKind(kind: HealthEvent.Kind): String = kind.name

    @TypeConverter
    @JvmStatic
    fun toHealthKind(name: String): HealthEvent.Kind = HealthEvent.Kind.valueOf(name)
}
