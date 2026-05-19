package com.flowdroid.engine

import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.flow.Trigger
import com.flowdroid.engine.TriggerMatcher.MatchOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TriggerMatcherTest {

    private fun event(
        pkg: String = "com.example.app",
        title: String? = "Echo slot available",
        text: String? = "Tap to join",
        bigText: String? = null,
        actionLabels: List<String> = listOf("Acknowledge", "Dismiss"),
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
    ) = NotificationEvent(
        sbnKey = "0|$pkg|7|tag|0",
        packageName = pkg,
        postTimeMillis = 1L,
        notificationPostTimeMillis = 1L,
        title = title,
        text = text,
        bigText = bigText,
        subText = null,
        tickerText = null,
        channelId = "default",
        groupKey = null,
        isOngoing = isOngoing,
        isClearable = true,
        isGroupSummary = isGroupSummary,
        importance = 3,
        notificationId = 7,
        actionLabels = actionLabels,
        rawExtrasJson = null,
    )

    @Test fun `null filters match everything`() {
        val t = Trigger.NotificationPosted()
        assertThat(TriggerMatcher.matches(t, event())).isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `exact package match passes`() {
        val t = Trigger.NotificationPosted(packageName = "com.example.app")
        assertThat(TriggerMatcher.matches(t, event())).isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `package mismatch returns no-match with reason`() {
        val t = Trigger.NotificationPosted(packageName = "com.other")
        val r = TriggerMatcher.matches(t, event())
        assertThat(r).isInstanceOf(MatchOutcome.NoMatch::class.java)
        assertThat((r as MatchOutcome.NoMatch).reason).contains("packageName")
    }

    @Test fun `title regex partial match works (containsMatchIn)`() {
        val t = Trigger.NotificationPosted(titleRegex = "Echo")
        assertThat(TriggerMatcher.matches(t, event(title = "Echo slot available")))
            .isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `title regex no match yields NoMatch`() {
        val t = Trigger.NotificationPosted(titleRegex = "^Hello$")
        val r = TriggerMatcher.matches(t, event(title = "Echo slot available"))
        assertThat(r).isInstanceOf(MatchOutcome.NoMatch::class.java)
    }

    @Test fun `text regex matches text field`() {
        val t = Trigger.NotificationPosted(textRegex = "join")
        assertThat(TriggerMatcher.matches(t, event(text = "Tap to join")))
            .isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `text regex falls back to bigText`() {
        val t = Trigger.NotificationPosted(textRegex = "details")
        assertThat(
            TriggerMatcher.matches(
                t,
                event(text = "Short", bigText = "Long details here"),
            )
        ).isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `action label regex requires matching label`() {
        val t = Trigger.NotificationPosted(actionLabelRegex = "^Acknowledge$")
        assertThat(
            TriggerMatcher.matches(t, event(actionLabels = listOf("Acknowledge", "Dismiss")))
        ).isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `action label regex with no matching label yields NoMatch`() {
        val t = Trigger.NotificationPosted(actionLabelRegex = "^Snooze$")
        val r = TriggerMatcher.matches(t, event(actionLabels = listOf("Acknowledge", "Dismiss")))
        assertThat(r).isInstanceOf(MatchOutcome.NoMatch::class.java)
    }

    @Test fun `excludeOngoing skips ongoing notifications`() {
        val t = Trigger.NotificationPosted(excludeOngoing = true)
        val r = TriggerMatcher.matches(t, event(isOngoing = true))
        assertThat(r).isInstanceOf(MatchOutcome.NoMatch::class.java)
        assertThat((r as MatchOutcome.NoMatch).reason).contains("ongoing")
    }

    @Test fun `excludeOngoing false allows ongoing`() {
        val t = Trigger.NotificationPosted(excludeOngoing = false)
        assertThat(TriggerMatcher.matches(t, event(isOngoing = true)))
            .isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `excludeOngoing skips group summary`() {
        val t = Trigger.NotificationPosted(excludeOngoing = true)
        val r = TriggerMatcher.matches(t, event(isGroupSummary = true))
        assertThat(r).isInstanceOf(MatchOutcome.NoMatch::class.java)
    }

    @Test fun `all filters AND together`() {
        val t = Trigger.NotificationPosted(
            packageName = "com.example.app",
            titleRegex = "Echo",
            textRegex = "join",
            actionLabelRegex = "Acknowledge",
            excludeOngoing = true,
        )
        assertThat(TriggerMatcher.matches(t, event())).isEqualTo(MatchOutcome.Matched)
    }

    @Test fun `invalid regex returns InvalidRegex outcome`() {
        val t = Trigger.NotificationPosted(titleRegex = "[unclosed")
        val r = TriggerMatcher.matches(t, event())
        assertThat(r).isInstanceOf(MatchOutcome.InvalidRegex::class.java)
        assertThat((r as MatchOutcome.InvalidRegex).field).isEqualTo("titleRegex")
    }

    @Test fun `regex compilation is cached (smoke test)`() {
        val t = Trigger.NotificationPosted(titleRegex = "Echo")
        val e = event()
        // Many calls should not throw and should be fast.
        repeat(1000) {
            assertThat(TriggerMatcher.matches(t, e)).isEqualTo(MatchOutcome.Matched)
        }
    }
}
