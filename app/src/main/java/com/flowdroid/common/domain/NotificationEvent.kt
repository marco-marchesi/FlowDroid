package com.flowdroid.common.domain

/**
 * Domain representation of a notification observed by the listener.
 *
 * Stored verbatim in Phase 0; in later phases this becomes the trigger event for [com.flowdroid].
 * Field names map directly to magic-text bindings (see PLAN.md §5.3).
 */
data class NotificationEvent(
    val id: Long = 0,                         // Room rowid
    val sbnKey: String,                       // StatusBarNotification.key — stable across posts of same id
    val packageName: String,
    val postTimeMillis: Long,                 // captured via Clock.nowMillis at receipt
    val notificationPostTimeMillis: Long,     // SBN.postTime, the OS-reported timestamp
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val tickerText: String?,
    val channelId: String?,
    val groupKey: String?,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val isGroupSummary: Boolean,
    val importance: Int?,
    val notificationId: Int,                  // SBN.id (per-app id)
    val actionLabels: List<String>,           // labels of Notification.Action[]
    val rawExtrasJson: String?,               // best-effort JSON dump of extras for debugging
)
