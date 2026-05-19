package com.flowdroid.data

import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.ClickMode
import com.flowdroid.common.flow.CompareOp
import com.flowdroid.common.flow.Condition
import com.flowdroid.common.flow.LoopMode
import com.flowdroid.common.flow.SwipeDirection
import com.flowdroid.common.flow.TextMatch
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.UiKey
import com.flowdroid.common.flow.UiTargetMode
import com.flowdroid.data.db.deserializeActions
import com.flowdroid.data.db.deserializeTrigger
import com.flowdroid.data.db.deserializeTriggers
import com.flowdroid.data.db.serializeActions
import com.flowdroid.data.db.serializeTrigger
import com.flowdroid.data.db.serializeTriggers
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.Test

/**
 * Verifies the JSON encoding for the sealed [Trigger] and [Action] hierarchies in
 * [com.flowdroid.common.flow.Flow].
 *
 * Forward-compatibility contract:
 *  - Unknown subtype discriminator → [SerializationException] (loud failure, caller's job to log
 *    and skip).
 *  - Missing required field → [SerializationException] (loud failure, never silently produce a
 *    half-built object).
 */
class FlowSerializationTest {

    /* ---------- Trigger round-trips ---------- */

    @Test
    fun `round-trip NotificationPosted with all fields populated`() {
        val original = Trigger.NotificationPosted(
            packageName = "com.example.app",
            titleRegex = "^Match.*",
            textRegex = "near you",
            actionLabelRegex = "^Acknowledge$",
            excludeOngoing = false,
            debounceMillis = 5_000L,
        )
        val json = serializeTrigger(original)
        val back = deserializeTrigger(json)
        assertThat(back).isEqualTo(original)
    }

    @Test
    fun `round-trip NotificationPosted with defaults`() {
        val original = Trigger.NotificationPosted()
        val json = serializeTrigger(original)
        val back = deserializeTrigger(json)
        assertThat(back).isEqualTo(original)
    }

    @Test
    fun `serialized trigger includes type discriminator`() {
        val json = serializeTrigger(Trigger.NotificationPosted(packageName = "x"))
        assertThat(json).contains("\"type\":\"NotificationPosted\"")
    }

    /* ---------- Action round-trips ---------- */

    @Test
    fun `round-trip ClickNotificationAction`() {
        val original = Action.ClickNotificationAction(
            labelRegex = "^Acknowledge$",
            dismissAfter = true,
            continueOnError = true,
        )
        val json = serializeActions(listOf(original))
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip LaunchApp`() {
        val original = Action.LaunchApp(
            packageName = "com.example.app",
            activityClass = "com.example.app.MainActivity",
            continueOnError = false,
        )
        val json = serializeActions(listOf(original))
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip PostNotification`() {
        val original = Action.PostNotification(
            title = "Hi",
            text = "World",
            continueOnError = true,
        )
        val json = serializeActions(listOf(original))
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip preserves order of heterogeneous action list`() {
        val originals = listOf(
            Action.PostNotification(title = "a", text = "x"),
            Action.LaunchApp(packageName = "com.example"),
            Action.ClickNotificationAction(labelRegex = "ok"),
            Action.PostNotification(title = "b", text = "y"),
        )
        val json = serializeActions(originals)
        val back = deserializeActions(json)
        assertThat(back).containsExactlyElementsIn(originals).inOrder()
    }

    @Test
    fun `empty actions list round-trips`() {
        val json = serializeActions(emptyList())
        val back = deserializeActions(json)
        assertThat(back).isEmpty()
    }

    /* ---------- Forward-compat / failure modes ---------- */

    @Test
    fun `unknown trigger type throws SerializationException`() {
        val payload = """{"type":"FutureKind","packageName":"x"}"""
        try {
            deserializeTrigger(payload)
            error("expected SerializationException")
        } catch (e: SerializationException) {
            // expected
            assertThat(e).isNotNull()
        }
    }

    @Test
    fun `unknown action type throws SerializationException`() {
        val payload = """[{"type":"FutureAction","title":"x"}]"""
        try {
            deserializeActions(payload)
            error("expected SerializationException")
        } catch (e: SerializationException) {
            assertThat(e).isNotNull()
        }
    }

    @Test
    fun `missing required field on Action fails cleanly`() {
        // ClickNotificationAction requires labelRegex; omit it.
        val payload = """[{"type":"ClickNotificationAction"}]"""
        try {
            deserializeActions(payload)
            error("expected SerializationException")
        } catch (e: SerializationException) {
            assertThat(e).isNotNull()
        }
    }

    @Test
    fun `missing required field on PostNotification fails cleanly`() {
        // PostNotification requires title and text.
        val payload = """[{"type":"PostNotification","title":"only-title"}]"""
        try {
            deserializeActions(payload)
            error("expected SerializationException")
        } catch (e: SerializationException) {
            assertThat(e).isNotNull()
        }
    }

    @Test
    fun `malformed JSON fails cleanly`() {
        val payload = """{"type":"NotificationPosted","""
        try {
            deserializeTrigger(payload)
            error("expected SerializationException")
        } catch (e: SerializationException) {
            assertThat(e).isNotNull()
        }
    }

    /* ---------- New (Phase 2) action round-trips ---------- */

    @Test
    fun `round-trip Delay`() {
        val original = Action.Delay(millis = 1234L, continueOnError = true)
        val json = serializeActions(listOf(original))
        assertThat(json).contains("\"type\":\"Delay\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip KillApp`() {
        val original = Action.KillApp(packageName = "com.example.app", continueOnError = true)
        val json = serializeActions(listOf(original))
        assertThat(json).contains("\"type\":\"KillApp\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip UiClick covers every enum value across rows`() {
        val originals = mutableListOf<Action>()
        for (target in UiTargetMode.values()) {
            for (textMatch in TextMatch.values()) {
                for (clickMode in ClickMode.values()) {
                    originals += Action.UiClick(
                        targetMode = target,
                        x = 12.5f,
                        y = 99.0f,
                        text = "label",
                        textMatch = textMatch,
                        contentDescription = "desc",
                        viewId = "com.x:id/btn",
                        inPackage = "com.x",
                        clickMode = clickMode,
                        longPressDurationMs = 800L,
                        timeoutMs = 7_500L,
                        continueOnError = true,
                    )
                }
            }
        }
        val json = serializeActions(originals)
        assertThat(json).contains("\"type\":\"UiClick\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactlyElementsIn(originals).inOrder()
    }

    @Test
    fun `round-trip UiSwipe covers every direction including null`() {
        val originals = mutableListOf<Action>(
            Action.UiSwipe(
                direction = null,
                fromX = 1f, fromY = 2f, toX = 3f, toY = 4f,
                durationMs = 200L,
                continueOnError = true,
            ),
        )
        for (direction in SwipeDirection.values()) {
            originals += Action.UiSwipe(
                direction = direction,
                fromX = 0f, fromY = 0f, toX = 0f, toY = 0f,
                durationMs = 500L,
                continueOnError = false,
            )
        }
        val json = serializeActions(originals)
        assertThat(json).contains("\"type\":\"UiSwipe\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactlyElementsIn(originals).inOrder()
    }

    @Test
    fun `round-trip UiTypeText with and without pressEnterAfter`() {
        val originals = listOf(
            Action.UiTypeText(text = "hello", pressEnterAfter = false, continueOnError = false),
            Action.UiTypeText(text = "search me", pressEnterAfter = true, continueOnError = true),
        )
        val json = serializeActions(originals)
        assertThat(json).contains("\"type\":\"UiTypeText\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactlyElementsIn(originals).inOrder()
    }

    @Test
    fun `round-trip UiPressKey covers every UiKey value`() {
        val originals = UiKey.values().map { Action.UiPressKey(key = it, continueOnError = false) }
        val json = serializeActions(originals)
        assertThat(json).contains("\"type\":\"UiPressKey\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactlyElementsIn(originals).inOrder()
    }

    /* ---------- Trigger-list round-trips ---------- */

    @Test
    fun `round-trip multi-trigger list preserves order`() {
        val originals = listOf(
            Trigger.NotificationPosted(packageName = "com.a"),
            Trigger.NotificationPosted(packageName = "com.b", titleRegex = "^Hi"),
            Trigger.NotificationPosted(packageName = "com.c", debounceMillis = 9_000L),
        )
        val json = serializeTriggers(originals)
        val back = deserializeTriggers(json)
        assertThat(back).containsExactlyElementsIn(originals).inOrder()
    }

    @Test
    fun `empty trigger list round-trips`() {
        val json = serializeTriggers(emptyList())
        val back = deserializeTriggers(json)
        assertThat(back).isEmpty()
    }

    @Test
    fun `round-trip If with nested actions in both branches`() {
        val original = Action.If(
            condition = Condition("{var.status}", CompareOp.EQUALS, "200"),
            thenActions = listOf(
                Action.PostNotification(title = "ok", text = "{var.status}"),
                Action.Delay(millis = 500L),
            ),
            elseActions = listOf(
                Action.PostNotification(title = "fail", text = "code {var.status}"),
            ),
            label = "Branch on HTTP status",
        )
        val json = serializeActions(listOf(original))
        assertThat(json).contains("\"type\":\"If\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip If nested inside another If`() {
        val inner = Action.If(
            condition = Condition("x", CompareOp.NOT_EQUALS, "y"),
            thenActions = listOf(Action.Delay(millis = 1L)),
        )
        val outer = Action.If(
            condition = Condition("a", CompareOp.IS_NOT_BLANK),
            thenActions = listOf(inner),
        )
        val back = deserializeActions(serializeActions(listOf(outer)))
        assertThat(back).containsExactly(outer)
    }

    @Test
    fun `round-trip Loop count with nested actions`() {
        val original = Action.Loop(
            mode = LoopMode.COUNT,
            count = 5,
            actions = listOf(
                Action.Delay(millis = 100L),
                Action.PostNotification(title = "i", text = "{var.index}"),
            ),
            label = "Retry 5x",
        )
        val json = serializeActions(listOf(original))
        assertThat(json).contains("\"type\":\"Loop\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip Loop foreach with custom item and index var names`() {
        val original = Action.Loop(
            mode = LoopMode.FOREACH_LINES,
            list = "{var.body}",
            itemVar = "line",
            indexVar = "n",
            actions = listOf(Action.PostNotification(title = "row", text = "{var.line}")),
        )
        val back = deserializeActions(serializeActions(listOf(original)))
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip If containing Loop containing If`() {
        val deeplyNested = Action.If(
            condition = Condition("{var.item}", CompareOp.EQUALS, "stop"),
            thenActions = listOf(Action.Delay(millis = 1L)),
        )
        val loop = Action.Loop(
            mode = LoopMode.FOREACH_LINES,
            list = "a\nb\nstop",
            actions = listOf(deeplyNested),
        )
        val outer = Action.If(
            condition = Condition("{var.enabled}", CompareOp.EQUALS, "true"),
            thenActions = listOf(loop),
        )
        val back = deserializeActions(serializeActions(listOf(outer)))
        assertThat(back).containsExactly(outer)
    }

    @Test
    fun `round-trip TryCatch with all three branches populated`() {
        val original = Action.TryCatch(
            tryActions = listOf(
                Action.Http(
                    method = com.flowdroid.common.flow.HttpMethod.POST,
                    url = "https://api.example.com/subscribe",
                ),
            ),
            catchActions = listOf(
                Action.PostNotification(title = "Subscribe failed", text = "{var.err}"),
            ),
            finallyActions = listOf(
                Action.Delay(millis = 50L),
            ),
            intoErrorVar = "err",
            label = "Echo subscribe",
        )
        val json = serializeActions(listOf(original))
        assertThat(json).contains("\"type\":\"TryCatch\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip TryCatch nested inside Loop inside If`() {
        val inner = Action.TryCatch(
            tryActions = listOf(Action.Delay(millis = 1L)),
            catchActions = listOf(Action.Delay(millis = 2L)),
        )
        val loop = Action.Loop(
            mode = LoopMode.COUNT,
            count = 3,
            actions = listOf(inner),
        )
        val outer = Action.If(
            condition = Condition("{var.go}", CompareOp.EQUALS, "yes"),
            thenActions = listOf(loop),
        )
        val back = deserializeActions(serializeActions(listOf(outer)))
        assertThat(back).containsExactly(outer)
    }

    @Test
    fun `round-trip Http with retry fields`() {
        val original = Action.Http(
            method = com.flowdroid.common.flow.HttpMethod.POST,
            url = "https://api.example.com/x",
            retries = 3,
            retryBackoffMs = 250L,
            retryOnStatusMin = 502,
            retryOnStatusMax = 504,
        )
        val json = serializeActions(listOf(original))
        assertThat(json).contains("\"retries\":3")
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `old Http JSON without retry fields decodes with defaults (forward compat)`() {
        // A pre-Phase-8 export wouldn't carry retries/retryBackoffMs/retryOnStatus* — the deserializer
        // must accept this and fall back to the defaults declared on Action.Http.
        val payload = """[{"type":"Http","method":"GET","url":"https://example.com"}]"""
        val back = deserializeActions(payload)
        assertThat(back).hasSize(1)
        val http = back[0] as Action.Http
        assertThat(http.retries).isEqualTo(0)
        assertThat(http.retryBackoffMs).isEqualTo(1_000L)
        assertThat(http.retryOnStatusMin).isEqualTo(500)
        assertThat(http.retryOnStatusMax).isEqualTo(599)
    }

    @Test
    fun `round-trip UnlockScreen`() {
        val original = Action.UnlockScreen(timeoutMs = 8000L, label = "Wake")
        val json = serializeActions(listOf(original))
        assertThat(json).contains("\"type\":\"UnlockScreen\"")
        val back = deserializeActions(json)
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `round-trip If with empty branches`() {
        val original = Action.If(condition = Condition("", CompareOp.IS_BLANK))
        val back = deserializeActions(serializeActions(listOf(original)))
        assertThat(back).containsExactly(original)
    }

    @Test
    fun `unknown keys in payload are ignored for forward compatibility`() {
        // ignoreUnknownKeys = true: an older app reading a payload with extra fields should
        // succeed and ignore the unknown field.
        val payload = """
            {"type":"NotificationPosted","packageName":"com.x","unknownFutureField":"hello"}
        """.trimIndent()
        val back = deserializeTrigger(payload)
        assertThat(back).isInstanceOf(Trigger.NotificationPosted::class.java)
        assertThat((back as Trigger.NotificationPosted).packageName).isEqualTo("com.x")
    }
}
