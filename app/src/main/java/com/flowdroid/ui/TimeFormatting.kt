package com.flowdroid.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Small set of time-formatting helpers used by the Health and Log screens.
 *
 * We deliberately keep these as plain functions (no Composable wrapper) so they can be
 * unit-tested without the Android runtime. UI callers convert to remembered values.
 */

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/** Formats a wall-clock millis to "HH:mm:ss" in the device's local timezone. */
fun formatTimeOfDay(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val ldt: LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return "${pad2(ldt.hour)}:${pad2(ldt.minute)}:${pad2(ldt.second)}"
}

/** Formats a wall-clock millis to "yyyy-MM-dd HH:mm:ss" in the device's local timezone. */
fun formatAbsolute(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val ldt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return "${ldt.year}-${pad2(ldt.monthNumber)}-${pad2(ldt.dayOfMonth)} " +
            "${pad2(ldt.hour)}:${pad2(ldt.minute)}:${pad2(ldt.second)}"
}

/**
 * Renders a duration as "3m", "2h 14m", "12s", "5d".
 * For the Health screen and disconnect journal — terse, no fluff.
 */
fun formatDuration(millis: Long): String {
    if (millis < 0) return "0s"
    val totalSec = millis / 1000
    val days = totalSec / 86_400
    val hours = (totalSec % 86_400) / 3_600
    val minutes = (totalSec % 3_600) / 60
    val seconds = totalSec % 60
    return when {
        days > 0 -> if (hours > 0) "${days}d ${hours}h" else "${days}d"
        hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
        minutes > 0 -> if (seconds > 0 && minutes < 5) "${minutes}m ${seconds}s" else "${minutes}m"
        else -> "${seconds}s"
    }
}

/** Renders a relative timestamp ("3m ago", "just now", "2h ago"). */
fun formatRelative(eventMillis: Long, nowMillis: Long): String {
    val delta = nowMillis - eventMillis
    // Check "future" BEFORE "just now": a -3s delta means the event happened 3 seconds AFTER
    // the reference clock, which should surface as "in the future", not be hidden as "just now".
    if (delta < 0) return "in the future"
    if (delta < 5_000) return "just now"
    return "${formatDuration(delta)} ago"
}
