package com.flowdroid.ui.flows

import com.flowdroid.common.flow.Trigger
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TriggerCatalogTest {

    @Test fun `every entry has non-empty fields`() {
        TriggerCatalog.triggers.forEach { entry ->
            assertThat(entry.id).isNotEmpty()
            assertThat(entry.name).isNotEmpty()
            assertThat(entry.description).isNotEmpty()
            assertThat(entry.keywords).isNotEmpty()
        }
    }

    @Test fun `every factory produces a Trigger`() {
        TriggerCatalog.triggers.forEach { entry ->
            val produced: Trigger = entry.factory()
            assertThat(produced).isNotNull()
        }
    }

    @Test fun `empty search returns all triggers`() {
        assertThat(TriggerCatalog.searchTriggers("")).isEqualTo(TriggerCatalog.triggers)
    }

    @Test fun `search by family matches all entries in that family`() {
        val scheduleHits = TriggerCatalog.searchTriggers("schedule")
        assertThat(scheduleHits.map { it.id }).containsAtLeast("time_of_day", "interval")
        val externalHits = TriggerCatalog.searchTriggers("external")
        assertThat(externalHits.map { it.id }).contains("webhook")
        val notifHits = TriggerCatalog.searchTriggers("apps")
        assertThat(notifHits.map { it.id }).contains("notification_posted")
    }

    @Test fun `search by keyword matches`() {
        assertThat(TriggerCatalog.searchTriggers("cron").map { it.id })
            .containsAtLeast("time_of_day", "interval")
        assertThat(TriggerCatalog.searchTriggers("http").map { it.id }).contains("webhook")
        assertThat(TriggerCatalog.searchTriggers("notif").map { it.id }).contains("notification_posted")
    }

    @Test fun `search is case-insensitive`() {
        val lower = TriggerCatalog.searchTriggers("webhook")
        val upper = TriggerCatalog.searchTriggers("WEBHOOK")
        assertThat(lower).isEqualTo(upper)
    }

    @Test fun `webhook factory generates unique paths`() {
        val a = TriggerCatalog.triggers.first { it.id == "webhook" }.factory() as Trigger.Webhook
        val b = TriggerCatalog.triggers.first { it.id == "webhook" }.factory() as Trigger.Webhook
        assertThat(a.path).startsWith("hook-")
        assertThat(a.path).hasLength("hook-".length + 8)
        // Vanishingly unlikely collision: 16^8 ≈ 4.3B.
        assertThat(a.path).isNotEqualTo(b.path)
    }

    @Test fun `time of day factory has valid defaults`() {
        val t = TriggerCatalog.triggers.first { it.id == "time_of_day" }.factory() as Trigger.TimeOfDay
        assertThat(t.hour).isIn(0..23)
        assertThat(t.minute).isIn(0..59)
    }

    @Test fun `interval factory has valid default`() {
        val t = TriggerCatalog.triggers.first { it.id == "interval" }.factory() as Trigger.Interval
        assertThat(t.intervalMinutes).isIn(1..1440)
    }
}
