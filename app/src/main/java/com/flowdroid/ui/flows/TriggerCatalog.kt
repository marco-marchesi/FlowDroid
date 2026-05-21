package com.flowdroid.ui.flows

import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.WebhookMethod

/**
 * Catalogue of selectable trigger kinds, grouped into families. Mirrors [ActionCatalog] but for
 * triggers — drives the "Add trigger" picker sheet and the in-card trigger-type swap dropdown.
 *
 * Pure data; no Android imports — fast to unit-test off-device.
 */
object TriggerCatalog {

    /** Trigger families. Order here is the display order in the picker. */
    enum class TriggerFamily(val label: String) {
        APPS_NOTIFICATIONS("Apps & Notifications"),
        SCHEDULE("Schedule"),
        EXTERNAL("External"),
    }

    /**
     * A single selectable trigger kind.
     *
     * @property name        User-visible name shown as the row label.
     * @property description One-line hint shown under the name.
     * @property family      Section header it belongs to.
     * @property keywords    Extra terms the search index will match against beyond [name].
     * @property factory     Produces a freshly-initialised [Trigger] of this kind.
     */
    data class TriggerEntry(
        val id: String,
        val name: String,
        val description: String,
        val family: TriggerFamily,
        val keywords: List<String>,
        val factory: () -> Trigger,
    )

    /** Complete list of trigger kinds, ordered by family then alphabetically within family. */
    val triggers: List<TriggerEntry> = listOf(
        TriggerEntry(
            id = "notification_posted",
            name = "Notification posted",
            description = "Match notifications by package, title, text or action label.",
            family = TriggerFamily.APPS_NOTIFICATIONS,
            keywords = listOf("notif", "alert", "shade", "filter", "listener"),
            factory = { Trigger.NotificationPosted() },
        ),
        TriggerEntry(
            id = "time_of_day",
            name = "Time of day",
            description = "Fire at a specific HH:MM on selected weekdays.",
            family = TriggerFamily.SCHEDULE,
            keywords = listOf("schedule", "clock", "daily", "weekday", "cron", "alarm", "time"),
            factory = { Trigger.TimeOfDay(hour = 8, minute = 0) },
        ),
        TriggerEntry(
            id = "interval",
            name = "Interval",
            description = "Fire every N minutes, optionally within an active window.",
            family = TriggerFamily.SCHEDULE,
            keywords = listOf("schedule", "periodic", "repeat", "every", "minutes", "poll", "cron"),
            factory = { Trigger.Interval(intervalMinutes = 15) },
        ),
        TriggerEntry(
            id = "webhook",
            name = "Webhook",
            description = "Fire on an incoming HTTP request to a unique local path.",
            family = TriggerFamily.EXTERNAL,
            keywords = listOf("http", "post", "get", "url", "ifttt", "external", "callback", "api"),
            factory = {
                Trigger.Webhook(
                    path = "hook-" + (1..8).map { "0123456789abcdef".random() }.joinToString(""),
                    method = WebhookMethod.ANY,
                )
            },
        ),
        TriggerEntry(
            id = "mqtt_subscribe",
            name = "MQTT subscribe",
            description = "Fire when a message arrives on a subscribed MQTT topic. Supports + and # wildcards.",
            family = TriggerFamily.EXTERNAL,
            keywords = listOf("mqtt", "iot", "broker", "subscribe", "topic", "message", "mosquitto", "hivemq"),
            factory = { Trigger.MqttSubscribe(brokerUrl = "tcp://", topic = "#") },
        ),
    )

    /**
     * Filter [triggers] by a free-text query. Empty query returns all. Match is case-insensitive
     * against the name, description, keywords, and family label.
     */
    fun searchTriggers(query: String): List<TriggerEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return triggers
        return triggers.filter { it.matches(q) }
    }

    private fun TriggerEntry.matches(q: String): Boolean =
        name.lowercase().contains(q) ||
            description.lowercase().contains(q) ||
            family.label.lowercase().contains(q) ||
            keywords.any { it.lowercase().contains(q) }
}
