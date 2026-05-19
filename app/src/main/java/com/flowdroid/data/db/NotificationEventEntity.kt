package com.flowdroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowdroid.common.domain.NotificationEvent

/**
 * Room storage shape for [NotificationEvent].
 *
 * The schema mirrors the domain class field-for-field. Two indices are defined:
 *
 *  - `postTimeMillis DESC` is the dominant query pattern (newest-first feeds for the UI and the
 *    notification debounce checks done by the trigger engine). Room/SQLite cannot store a literal
 *    DESC index but a regular index on this column is used by the query planner for `ORDER BY ... DESC`.
 *  - `packageName` is used by per-app filters in flow definitions (`T-NOTIF-001`).
 *
 * `actionLabels` is persisted as a JSON string via [Converters.fromStringList].
 *
 * Failure modes are owned by the repository layer — entities here are pure data.
 */
@Entity(
    tableName = "notification_events",
    indices = [
        Index(value = ["postTimeMillis"], name = "idx_notif_post_time"),
        Index(value = ["packageName"], name = "idx_notif_package"),
    ],
)
data class NotificationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long,

    @ColumnInfo(name = "sbnKey")
    val sbnKey: String,

    @ColumnInfo(name = "packageName")
    val packageName: String,

    @ColumnInfo(name = "postTimeMillis")
    val postTimeMillis: Long,

    @ColumnInfo(name = "notificationPostTimeMillis")
    val notificationPostTimeMillis: Long,

    @ColumnInfo(name = "title")
    val title: String?,

    @ColumnInfo(name = "text")
    val text: String?,

    @ColumnInfo(name = "bigText")
    val bigText: String?,

    @ColumnInfo(name = "subText")
    val subText: String?,

    @ColumnInfo(name = "tickerText")
    val tickerText: String?,

    @ColumnInfo(name = "channelId")
    val channelId: String?,

    @ColumnInfo(name = "groupKey")
    val groupKey: String?,

    @ColumnInfo(name = "isOngoing")
    val isOngoing: Boolean,

    @ColumnInfo(name = "isClearable")
    val isClearable: Boolean,

    @ColumnInfo(name = "isGroupSummary")
    val isGroupSummary: Boolean,

    @ColumnInfo(name = "importance")
    val importance: Int?,

    @ColumnInfo(name = "notificationId")
    val notificationId: Int,

    @ColumnInfo(name = "actionLabels")
    val actionLabels: List<String>,

    @ColumnInfo(name = "rawExtrasJson")
    val rawExtrasJson: String?,
)

/** Map persistence row to the domain object used elsewhere in the codebase. */
fun NotificationEventEntity.toDomain(): NotificationEvent = NotificationEvent(
    id = id,
    sbnKey = sbnKey,
    packageName = packageName,
    postTimeMillis = postTimeMillis,
    notificationPostTimeMillis = notificationPostTimeMillis,
    title = title,
    text = text,
    bigText = bigText,
    subText = subText,
    tickerText = tickerText,
    channelId = channelId,
    groupKey = groupKey,
    isOngoing = isOngoing,
    isClearable = isClearable,
    isGroupSummary = isGroupSummary,
    importance = importance,
    notificationId = notificationId,
    actionLabels = actionLabels,
    rawExtrasJson = rawExtrasJson,
)

/**
 * Map a domain object to a row ready for insertion.
 *
 * The domain id of `0` is treated as "let Room assign a new rowid" — Room's autoGenerate works
 * because `0` is the documented sentinel for "not set". Callers should never pass a non-zero id
 * for a new event.
 */
fun NotificationEvent.toEntity(): NotificationEventEntity = NotificationEventEntity(
    id = id,
    sbnKey = sbnKey,
    packageName = packageName,
    postTimeMillis = postTimeMillis,
    notificationPostTimeMillis = notificationPostTimeMillis,
    title = title,
    text = text,
    bigText = bigText,
    subText = subText,
    tickerText = tickerText,
    channelId = channelId,
    groupKey = groupKey,
    isOngoing = isOngoing,
    isClearable = isClearable,
    isGroupSummary = isGroupSummary,
    importance = importance,
    notificationId = notificationId,
    actionLabels = actionLabels,
    rawExtrasJson = rawExtrasJson,
)
