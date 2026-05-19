package com.flowdroid.ui.flows

import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ClickMode
import com.flowdroid.common.flow.DayOfWeek
import com.flowdroid.common.flow.SwipeDirection
import com.flowdroid.common.flow.TextMatch
import com.flowdroid.common.flow.TimeRange
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.UiKey
import com.flowdroid.common.flow.UiTargetMode
import com.flowdroid.common.flow.WebhookMethod
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class FlowSummaryTest {

    @Test fun `trigger with package and title produces echo-style summary`() {
        val s = summarizeTrigger(
            Trigger.NotificationPosted(packageName = "com.example.app", titleRegex = "^Echo.*"),
        )
        assertThat(s).isEqualTo("Notification from com.example.app where title matches ^Echo.*")
    }

    @Test fun `trigger with no filters falls back to readable wording`() {
        val s = summarizeTrigger(Trigger.NotificationPosted())
        assertThat(s).isEqualTo("Notification from any app")
    }

    @Test fun `trigger combines all filters with 'and'`() {
        val s = summarizeTrigger(
            Trigger.NotificationPosted(
                packageName = "com.example.app",
                titleRegex = "^Echo.*",
                textRegex = "match",
                actionLabelRegex = "^Acknowledge$",
            ),
        )
        assertThat(s).contains("from com.example.app")
        assertThat(s).contains("title matches ^Echo.*")
        assertThat(s).contains("text matches match")
        assertThat(s).contains("action label matches ^Acknowledge$")
        assertThat(s).contains(" and ")
    }

    @Test fun `summarizeActions empty list returns no actions marker`() {
        assertThat(summarizeActions(emptyList())).isEqualTo("(no actions)")
    }

    @Test fun `summarizeActions joins multiple actions with semicolons`() {
        val s = summarizeActions(
            listOf(
                Action.ClickNotificationAction(labelRegex = "^Acknowledge$"),
                Action.PostNotification(title = "Joined", text = "ok"),
            ),
        )
        assertThat(s).isEqualTo("Click action button matching ^Acknowledge$; Post notification")
    }

    @ParameterizedTest
    @MethodSource("everyActionVariant")
    fun `every Action variant produces a non-empty summary`(action: Action) {
        val s = summarizeAction(action)
        assertThat(s).isNotEmpty()
        assertThat(s).isNotEqualTo("(no actions)")
    }

    @ParameterizedTest
    @MethodSource("everyTriggerVariant")
    fun `every Trigger variant produces a non-empty summary`(trigger: Trigger) {
        val s = summarizeTrigger(trigger)
        assertThat(s).isNotEmpty()
    }

    @Test fun `click action with dismissAfter shows hint`() {
        val s = summarizeAction(Action.ClickNotificationAction(labelRegex = "X", dismissAfter = true))
        assertThat(s).contains("dismiss after")
    }

    @Test fun `launch app with activityClass renders both`() {
        val s = summarizeAction(Action.LaunchApp(packageName = "com.foo", activityClass = ".Main"))
        assertThat(s).isEqualTo("Launch com.foo / .Main")
    }

    @Test fun `delay action renders in seconds when ms is a whole second`() {
        assertThat(summarizeAction(Action.Delay(millis = 1500L))).isEqualTo("Wait 1.5s")
        assertThat(summarizeAction(Action.Delay(millis = 2000L))).isEqualTo("Wait 2s")
        assertThat(summarizeAction(Action.Delay(millis = 500L))).isEqualTo("Wait 500ms")
    }

    @Test fun `kill app renders package`() {
        assertThat(summarizeAction(Action.KillApp(packageName = "com.foo"))).isEqualTo("Kill com.foo")
    }

    @Test fun `ui click by coordinates`() {
        val s = summarizeAction(
            Action.UiClick(
                targetMode = UiTargetMode.COORDINATES,
                x = 100f,
                y = 200f,
                clickMode = ClickMode.DOUBLE,
            ),
        )
        assertThat(s).isEqualTo("Double-tap at (100, 200)")
    }

    @Test fun `ui click by text single tap`() {
        val s = summarizeAction(
            Action.UiClick(
                targetMode = UiTargetMode.BY_TEXT,
                text = "Join",
                clickMode = ClickMode.SINGLE,
            ),
        )
        assertThat(s).isEqualTo("Tap text \"Join\"")
    }

    @Test fun `ui swipe direction`() {
        val s = summarizeAction(Action.UiSwipe(direction = SwipeDirection.UP))
        assertThat(s).isEqualTo("Swipe up")
    }

    @Test fun `ui swipe coordinates`() {
        val s = summarizeAction(
            Action.UiSwipe(
                direction = null,
                fromX = 100f,
                fromY = 200f,
                toX = 100f,
                toY = 800f,
            ),
        )
        assertThat(s).isEqualTo("Swipe (100,200)→(100,800)")
    }

    @Test fun `ui type text`() {
        val s = summarizeAction(Action.UiTypeText(text = "hello"))
        assertThat(s).isEqualTo("Type \"hello\"")
    }

    @Test fun `ui press key back`() {
        val s = summarizeAction(Action.UiPressKey(key = UiKey.BACK))
        assertThat(s).isEqualTo("Press Back")
    }

    @Test fun `summarizeTriggers joins with or`() {
        val s = summarizeTriggers(
            listOf(
                Trigger.NotificationPosted(packageName = "com.a"),
                Trigger.NotificationPosted(packageName = "com.b"),
            ),
        )
        assertThat(s).isEqualTo("Notification from com.a or Notification from com.b")
    }

    @Test fun `summarizeTriggers empty list returns no triggers marker`() {
        assertThat(summarizeTriggers(emptyList())).isEqualTo("(no triggers)")
    }

    @ParameterizedTest
    @MethodSource("timeOfDayCases")
    fun `time of day trigger summary`(trigger: Trigger.TimeOfDay, expected: String) {
        assertThat(summarizeTrigger(trigger)).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("intervalCases")
    fun `interval trigger summary`(trigger: Trigger.Interval, expected: String) {
        assertThat(summarizeTrigger(trigger)).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("webhookCases")
    fun `webhook trigger summary`(trigger: Trigger.Webhook, expected: String) {
        assertThat(summarizeTrigger(trigger)).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun everyActionVariant(): List<Action> = listOf(
            Action.ClickNotificationAction(labelRegex = "^Acknowledge$"),
            Action.ClickNotificationAction(labelRegex = "^Join$", dismissAfter = true),
            Action.LaunchApp(packageName = "com.foo"),
            Action.LaunchApp(packageName = "com.foo", activityClass = ".Main"),
            Action.PostNotification(title = "Hi", text = "There"),
            Action.Delay(millis = 1500L),
            Action.KillApp(packageName = "com.foo"),
            Action.UiClick(targetMode = UiTargetMode.BY_TEXT, text = "Go", textMatch = TextMatch.CONTAINS),
            Action.UiClick(targetMode = UiTargetMode.COORDINATES, x = 1f, y = 2f, clickMode = ClickMode.LONG),
            Action.UiClick(targetMode = UiTargetMode.BY_CONTENT_DESCRIPTION, contentDescription = "ok"),
            Action.UiClick(targetMode = UiTargetMode.BY_VIEW_ID, viewId = "com.app:id/btn", inPackage = "com.app"),
            Action.UiSwipe(direction = SwipeDirection.LEFT),
            Action.UiSwipe(direction = null, fromX = 1f, fromY = 2f, toX = 3f, toY = 4f),
            Action.UiTypeText(text = "hi", pressEnterAfter = true),
            Action.UiPressKey(key = UiKey.HOME),
            Action.UiPressKey(key = UiKey.ENTER),
        )

        @JvmStatic
        fun everyTriggerVariant(): List<Trigger> = listOf(
            Trigger.NotificationPosted(),
            Trigger.NotificationPosted(packageName = "com.example.app"),
            Trigger.NotificationPosted(packageName = "com.example.app", titleRegex = "^Echo"),
            Trigger.NotificationPosted(textRegex = "match"),
            Trigger.NotificationPosted(actionLabelRegex = "^Acknowledge$"),
            Trigger.TimeOfDay(hour = 8, minute = 0),
            Trigger.TimeOfDay(hour = 7, minute = 30, daysOfWeek = setOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI)),
            Trigger.Interval(intervalMinutes = 15),
            Trigger.Interval(intervalMinutes = 30, activeWindow = TimeRange(8, 0, 18, 0)),
            Trigger.Webhook(path = "hook-abc12345"),
            Trigger.Webhook(path = "hook-deadbeef", method = WebhookMethod.POST, secret = "topsecret"),
        )

        @JvmStatic
        fun timeOfDayCases(): List<Arguments> = listOf(
            Arguments.of(Trigger.TimeOfDay(hour = 8, minute = 0), "Daily at 08:00"),
            Arguments.of(
                Trigger.TimeOfDay(hour = 7, minute = 30, daysOfWeek = setOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI)),
                "Daily at 07:30 (on MON, WED, FRI)",
            ),
            Arguments.of(
                Trigger.TimeOfDay(hour = 23, minute = 5, daysOfWeek = DayOfWeek.entries.toSet()),
                "Daily at 23:05",
            ),
        )

        @JvmStatic
        fun intervalCases(): List<Arguments> = listOf(
            Arguments.of(Trigger.Interval(intervalMinutes = 15), "Every 15m"),
            Arguments.of(
                Trigger.Interval(intervalMinutes = 30, activeWindow = TimeRange(8, 0, 18, 0)),
                "Every 30m between 08:00–18:00",
            ),
            Arguments.of(Trigger.Interval(intervalMinutes = 1), "Every 1m"),
        )

        @JvmStatic
        fun webhookCases(): List<Arguments> = listOf(
            Arguments.of(Trigger.Webhook(path = "hook-abc12345"), "Webhook /hook/hook-abc12345"),
            Arguments.of(
                Trigger.Webhook(path = "hook-deadbeef", method = WebhookMethod.POST, secret = "x"),
                "Webhook /hook/hook-deadbeef",
            ),
            Arguments.of(Trigger.Webhook(path = "custom"), "Webhook /hook/custom"),
        )
    }
}
