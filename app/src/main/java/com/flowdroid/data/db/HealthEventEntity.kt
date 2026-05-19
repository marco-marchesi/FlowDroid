package com.flowdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowdroid.common.domain.HealthEvent

/**
 * Room storage shape for [HealthEvent].
 *
 * Indexed on `timestampMillis` (newest-first feeds, pruning) and `kind` (mostRecent-per-kind
 * lookup, count-of-kinds-in-window queries). The `kind` value is persisted as the enum's
 * [Enum.name] via [Converters].
 */
@Entity(
    tableName = "health_events",
    indices = [
        Index(value = ["timestampMillis"], name = "idx_health_timestamp"),
        Index(value = ["kind"], name = "idx_health_kind"),
    ],
)
data class HealthEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long,

    @ColumnInfo(name = "timestampMillis")
    val timestampMillis: Long,

    @ColumnInfo(name = "kind")
    val kind: HealthEvent.Kind,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "outcome")
    val outcome: String?,
)

fun HealthEventEntity.toDomain(): HealthEvent = HealthEvent(
    id = id,
    timestampMillis = timestampMillis,
    kind = kind,
    message = message,
    outcome = outcome,
)

fun HealthEvent.toEntity(): HealthEventEntity = HealthEventEntity(
    id = id,
    timestampMillis = timestampMillis,
    kind = kind,
    message = message,
    outcome = outcome,
)
