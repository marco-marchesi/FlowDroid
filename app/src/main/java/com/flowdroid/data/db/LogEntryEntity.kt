package com.flowdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowdroid.common.domain.LogEntry

/**
 * Room storage shape for [LogEntry].
 *
 * Indexed on `timestampMillis` to keep newest-first queries cheap and pruning fast.
 *
 * The `level` column stores [LogEntry.Level.name] (via [Converters]). We also persist the integer
 * `levelPriority` in its own column so the level-filter query can do a simple integer comparison
 * (`levelPriority >= :minPriority`) instead of relying on enum ordering — the latter would need a
 * subquery or CASE statement.
 */
@Entity(
    tableName = "log_entries",
    indices = [
        Index(value = ["timestampMillis"], name = "idx_log_timestamp"),
        Index(value = ["levelPriority"], name = "idx_log_level_priority"),
    ],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long,

    @ColumnInfo(name = "timestampMillis")
    val timestampMillis: Long,

    @ColumnInfo(name = "level")
    val level: LogEntry.Level,

    /**
     * Denormalised numeric priority. Kept in sync with [level]; updated only on insert so the
     * invariant `levelPriority == level.priority` holds. Used by the priority-filter index.
     */
    @ColumnInfo(name = "levelPriority")
    val levelPriority: Int,

    @ColumnInfo(name = "tag")
    val tag: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "fieldsJson")
    val fieldsJson: String?,

    @ColumnInfo(name = "throwableClass")
    val throwableClass: String?,

    @ColumnInfo(name = "throwableMessage")
    val throwableMessage: String?,

    @ColumnInfo(name = "stackTraceFirstLines")
    val stackTraceFirstLines: String?,
)

fun LogEntryEntity.toDomain(): LogEntry = LogEntry(
    id = id,
    timestampMillis = timestampMillis,
    level = level,
    tag = tag,
    message = message,
    fieldsJson = fieldsJson,
    throwableClass = throwableClass,
    throwableMessage = throwableMessage,
    stackTraceFirstLines = stackTraceFirstLines,
)

fun LogEntry.toEntity(): LogEntryEntity = LogEntryEntity(
    id = id,
    timestampMillis = timestampMillis,
    level = level,
    levelPriority = level.priority,
    tag = tag,
    message = message,
    fieldsJson = fieldsJson,
    throwableClass = throwableClass,
    throwableMessage = throwableMessage,
    stackTraceFirstLines = stackTraceFirstLines,
)
