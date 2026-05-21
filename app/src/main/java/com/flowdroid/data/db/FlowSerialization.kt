package com.flowdroid.data.db

import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Base64Mode
import com.flowdroid.common.flow.ClickMode
import com.flowdroid.common.flow.CompareOp
import com.flowdroid.common.flow.Condition
import com.flowdroid.common.flow.DayOfWeek
import com.flowdroid.common.flow.FileWriteMode
import com.flowdroid.common.flow.HashAlgorithm
import com.flowdroid.common.flow.LoopMode
import com.flowdroid.common.flow.MathOp
import com.flowdroid.common.flow.StringOp
import com.flowdroid.common.flow.TtsQueueMode
import com.flowdroid.common.flow.HttpBodyType
import com.flowdroid.common.flow.HttpHeader
import com.flowdroid.common.flow.HttpMethod
import com.flowdroid.common.flow.SwipeDirection
import com.flowdroid.common.flow.TextMatch
import com.flowdroid.common.flow.TimeRange
import com.flowdroid.common.flow.Trigger
import com.flowdroid.common.flow.UiKey
import com.flowdroid.common.flow.UiTargetMode
import com.flowdroid.common.flow.WebhookMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON encode/decode for the [Trigger] and [Action] hierarchies in
 * [com.flowdroid.common.flow.Flow].
 *
 * Why surrogates?
 * ----------------
 * [Trigger] and [Action] live in `com.flowdroid.common.flow` (the domain package). That package
 * intentionally has no `kotlinx.serialization` dependency — the domain model must stay
 * framework-agnostic. The data layer owns the on-disk shape, so we mirror the hierarchies
 * here as `@Serializable` surrogate classes and provide pure-function conversions between domain
 * and surrogate.
 *
 * Why explicit polymorphism?
 * --------------------------
 * `classDiscriminator = "type"` lets us write JSON like `{"type": "NotificationPosted", ...}`.
 * The discriminator is stable across releases — renaming a subtype requires a migration. We use
 * [SerialName] on every surrogate so the on-disk discriminator does not change if the Kotlin class
 * is renamed.
 *
 * Forward compatibility:
 *  - Unknown JSON keys are ignored (`ignoreUnknownKeys = true`), so an older app reading a newer DB
 *    silently drops fields it doesn't understand. Acceptable because the engine reads required
 *    fields by name.
 *  - Unknown discriminator values (a future Trigger or Action kind) cause a typed
 *    [SerializationException] from kotlinx.serialization. We surface it cleanly — callers in
 *    [com.flowdroid.data.repo.FlowRepositoryImpl] catch it, log a WARN, and skip the malformed row.
 *
 * Missing required fields fail loudly with [SerializationException]; we never half-build an object.
 */
internal object FlowSerialization {

    /**
     * Discriminator values embedded in JSON. Stable across releases — renaming a subtype requires
     * a migration. Kept as named constants so a reverse-grep finds every place that references the
     * on-disk shape.
     */
    const val TYPE_NOTIFICATION_POSTED: String = "NotificationPosted"
    const val TYPE_TIME_OF_DAY: String = "TimeOfDay"
    const val TYPE_INTERVAL: String = "Interval"
    const val TYPE_WEBHOOK: String = "Webhook"
    const val TYPE_CLICK_NOTIFICATION_ACTION: String = "ClickNotificationAction"
    const val TYPE_LAUNCH_APP: String = "LaunchApp"
    const val TYPE_POST_NOTIFICATION: String = "PostNotification"
    const val TYPE_DELAY: String = "Delay"
    const val TYPE_KILL_APP: String = "KillApp"
    const val TYPE_UI_CLICK: String = "UiClick"
    const val TYPE_UI_SWIPE: String = "UiSwipe"
    const val TYPE_UI_TYPE_TEXT: String = "UiTypeText"
    const val TYPE_UI_PRESS_KEY: String = "UiPressKey"
    const val TYPE_HTTP: String = "Http"
    const val TYPE_SET_VARIABLE: String = "SetVariable"
    const val TYPE_READ_FILE: String = "ReadFile"
    const val TYPE_WRITE_FILE: String = "WriteFile"
    const val TYPE_BASE64: String = "Base64"
    const val TYPE_HASH: String = "Hash"
    const val TYPE_IF: String = "If"
    const val TYPE_LOOP: String = "Loop"
    const val TYPE_TRY_CATCH: String = "TryCatch"
    const val TYPE_UNLOCK_SCREEN: String = "UnlockScreen"
    const val TYPE_TOAST: String = "Toast"
    const val TYPE_OPEN_URL: String = "OpenUrl"
    const val TYPE_COPY_TO_CLIPBOARD: String = "CopyToClipboard"
    const val TYPE_GET_CLIPBOARD: String = "GetClipboard"
    const val TYPE_VIBRATE: String = "Vibrate"
    const val TYPE_TTS: String = "Tts"
    const val TYPE_MATH: String = "Math"
    const val TYPE_STRING_TRANSFORM: String = "StringTransform"
    const val TYPE_DATE_FORMAT: String = "DateFormat"
    const val TYPE_LOCK_SCREEN: String = "LockScreen"
    const val TYPE_SEND_SMS: String = "SendSms"
    const val TYPE_SEND_WHATSAPP: String = "SendWhatsApp"
    const val TYPE_SEND_TELEGRAM: String = "SendTelegram"
    const val TYPE_SEND_EMAIL: String = "SendEmail"
    const val TYPE_MQTT_PUBLISH: String = "MqttPublish"
    const val TYPE_MQTT_SUBSCRIBE: String = "MqttSubscribe"

    /**
     * The shared [Json] instance for trigger / action serialisation. Configured for:
     *  - `encodeDefaults = true` so the on-disk JSON is self-describing even when fields equal
     *    their defaults (cheaper to diff and review).
     *  - `classDiscriminator = "type"` for stable subtype tagging.
     *  - `ignoreUnknownKeys = true` for forward compatibility.
     *
     * No explicit `SerializersModule` is needed: [TriggerSurrogate] and [ActionSurrogate] are
     * `sealed` and `@Serializable`, so kotlinx.serialization auto-registers their subclasses by
     * `@SerialName`.
     */
    val json: Json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Trigger surrogates                                                                              */
/* ---------------------------------------------------------------------------------------------- */

/**
 * Serializable mirror of [com.flowdroid.common.flow.Trigger]. Every domain subtype has a
 * corresponding surrogate subclass annotated with a stable [SerialName].
 */
@Serializable
internal sealed class TriggerSurrogate {

    @Serializable
    @SerialName(FlowSerialization.TYPE_NOTIFICATION_POSTED)
    data class NotificationPosted(
        val packageName: String? = null,
        val titleRegex: String? = null,
        val textRegex: String? = null,
        val actionLabelRegex: String? = null,
        val excludeOngoing: Boolean = true,
        val debounceMillis: Long = 1500L,
        val label: String? = null,
    ) : TriggerSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_TIME_OF_DAY)
    data class TimeOfDay(
        val hour: Int,
        val minute: Int,
        val daysOfWeek: Set<DayOfWeek> = emptySet(),
        val label: String? = null,
    ) : TriggerSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_INTERVAL)
    data class Interval(
        val intervalMinutes: Int,
        val activeWindow: TimeRangeSurrogate? = null,
        val label: String? = null,
    ) : TriggerSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_WEBHOOK)
    data class Webhook(
        val path: String,
        val method: WebhookMethod = WebhookMethod.ANY,
        val secret: String? = null,
        val label: String? = null,
    ) : TriggerSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_MQTT_SUBSCRIBE)
    data class MqttSubscribe(
        val brokerUrl: String,
        val topic: String,
        val qos: Int = 0,
        val username: String = "",
        val password: String = "",
        val clientId: String = "",
        val payloadRegex: String = "",
        val label: String? = null,
    ) : TriggerSurrogate()
}

/** Mirror of [TimeRange] for serialisation. Same shape; lives top-level to satisfy KSP. */
@Serializable
internal data class TimeRangeSurrogate(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
)

internal fun Trigger.toSurrogate(): TriggerSurrogate = when (this) {
    is Trigger.NotificationPosted -> TriggerSurrogate.NotificationPosted(
        packageName = packageName,
        titleRegex = titleRegex,
        textRegex = textRegex,
        actionLabelRegex = actionLabelRegex,
        excludeOngoing = excludeOngoing,
        debounceMillis = debounceMillis,
        label = label,
    )
    is Trigger.TimeOfDay -> TriggerSurrogate.TimeOfDay(
        hour = hour,
        minute = minute,
        daysOfWeek = daysOfWeek,
        label = label,
    )
    is Trigger.Interval -> TriggerSurrogate.Interval(
        intervalMinutes = intervalMinutes,
        activeWindow = activeWindow?.let {
            TimeRangeSurrogate(it.startHour, it.startMinute, it.endHour, it.endMinute)
        },
        label = label,
    )
    is Trigger.Webhook -> TriggerSurrogate.Webhook(
        path = path,
        method = method,
        secret = secret,
        label = label,
    )
    is Trigger.MqttSubscribe -> TriggerSurrogate.MqttSubscribe(
        brokerUrl = brokerUrl,
        topic = topic,
        qos = qos,
        username = username,
        password = password,
        clientId = clientId,
        payloadRegex = payloadRegex,
        label = label,
    )
    // Action and Trigger are intentionally not sealed (test code creates ad-hoc subtypes).
    // Production should never hit this branch; if it does, we fail loud rather than silently
    // dropping the flow.
    else -> throw IllegalArgumentException("Unknown Trigger subtype: ${this::class.qualifiedName}")
}

internal fun TriggerSurrogate.toDomain(): Trigger = when (this) {
    is TriggerSurrogate.NotificationPosted -> Trigger.NotificationPosted(
        packageName = packageName,
        titleRegex = titleRegex,
        textRegex = textRegex,
        actionLabelRegex = actionLabelRegex,
        excludeOngoing = excludeOngoing,
        debounceMillis = debounceMillis,
        label = label,
    )
    is TriggerSurrogate.TimeOfDay -> Trigger.TimeOfDay(
        hour = hour,
        minute = minute,
        daysOfWeek = daysOfWeek,
        label = label,
    )
    is TriggerSurrogate.Interval -> Trigger.Interval(
        intervalMinutes = intervalMinutes,
        activeWindow = activeWindow?.let {
            TimeRange(it.startHour, it.startMinute, it.endHour, it.endMinute)
        },
        label = label,
    )
    is TriggerSurrogate.Webhook -> Trigger.Webhook(
        path = path,
        method = method,
        secret = secret,
        label = label,
    )
    is TriggerSurrogate.MqttSubscribe -> Trigger.MqttSubscribe(
        brokerUrl = brokerUrl,
        topic = topic,
        qos = qos,
        username = username,
        password = password,
        clientId = clientId,
        payloadRegex = payloadRegex,
        label = label,
    )
}

/* ---------------------------------------------------------------------------------------------- */
/* Action surrogates                                                                               */
/* ---------------------------------------------------------------------------------------------- */

@Serializable
internal sealed class ActionSurrogate {

    @Serializable
    @SerialName(FlowSerialization.TYPE_CLICK_NOTIFICATION_ACTION)
    data class ClickNotificationAction(
        val labelRegex: String,
        val dismissAfter: Boolean = false,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_LAUNCH_APP)
    data class LaunchApp(
        val packageName: String,
        val activityClass: String? = null,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_POST_NOTIFICATION)
    data class PostNotification(
        val title: String,
        val text: String,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_DELAY)
    data class Delay(
        val millis: Long,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_KILL_APP)
    data class KillApp(
        val packageName: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_UI_CLICK)
    data class UiClick(
        val targetMode: UiTargetMode,
        val x: Float = 0f,
        val y: Float = 0f,
        val text: String? = null,
        val textMatch: TextMatch = TextMatch.EXACT,
        val contentDescription: String? = null,
        val viewId: String? = null,
        val inPackage: String? = null,
        val clickMode: ClickMode = ClickMode.SINGLE,
        val longPressDurationMs: Long = 600L,
        val timeoutMs: Long = 5000L,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_UI_SWIPE)
    data class UiSwipe(
        val direction: SwipeDirection? = null,
        val fromX: Float = 0f,
        val fromY: Float = 0f,
        val toX: Float = 0f,
        val toY: Float = 0f,
        val durationMs: Long = 300L,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_UI_TYPE_TEXT)
    data class UiTypeText(
        val text: String,
        val pressEnterAfter: Boolean = false,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_UI_PRESS_KEY)
    data class UiPressKey(
        val key: UiKey,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_HTTP)
    data class Http(
        val method: HttpMethod,
        val url: String,
        val headers: List<HttpHeaderSurrogate> = emptyList(),
        val body: String? = null,
        val bodyContentType: HttpBodyType = HttpBodyType.NONE,
        val timeoutMs: Long = 30_000L,
        val expectStatusMin: Int = 200,
        val expectStatusMax: Int = 299,
        val storeStatusInVar: String? = null,
        val storeBodyInVar: String? = null,
        // Phase 8 — retry fields. Defaulted so older payloads (pre-retry) deserialize cleanly.
        val retries: Int = 0,
        val retryBackoffMs: Long = 1_000L,
        val retryOnStatusMin: Int = 500,
        val retryOnStatusMax: Int = 599,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_SET_VARIABLE)
    data class SetVariable(
        val name: String,
        val value: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_READ_FILE)
    data class ReadFile(
        val path: String,
        val intoVar: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_WRITE_FILE)
    data class WriteFile(
        val path: String,
        val content: String,
        val mode: FileWriteMode = FileWriteMode.OVERWRITE,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_BASE64)
    data class Base64(
        val input: String,
        val mode: Base64Mode,
        val intoVar: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_HASH)
    data class Hash(
        val input: String,
        val algorithm: HashAlgorithm,
        val intoVar: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    /**
     * Conditional execution. Carries its own nested `thenActions` / `elseActions` lists as
     * surrogate elements — kotlinx.serialization handles this recursion natively because
     * [ActionSurrogate] is sealed and `@Serializable`.
     */
    @Serializable
    @SerialName(FlowSerialization.TYPE_IF)
    data class If(
        val condition: ConditionSurrogate,
        val thenActions: List<ActionSurrogate> = emptyList(),
        val elseActions: List<ActionSurrogate> = emptyList(),
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    /**
     * Repeated execution surrogate. `actions` is recursive — nested loops and ifs serialise via the
     * same sealed [ActionSurrogate] hierarchy.
     */
    @Serializable
    @SerialName(FlowSerialization.TYPE_LOOP)
    data class Loop(
        val mode: LoopMode = LoopMode.COUNT,
        val count: Int = 1,
        val list: String = "",
        val itemVar: String = "item",
        val indexVar: String = "index",
        val actions: List<ActionSurrogate> = emptyList(),
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_TRY_CATCH)
    data class TryCatch(
        val tryActions: List<ActionSurrogate> = emptyList(),
        val catchActions: List<ActionSurrogate> = emptyList(),
        val finallyActions: List<ActionSurrogate> = emptyList(),
        val intoErrorVar: String = "",
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable
    @SerialName(FlowSerialization.TYPE_UNLOCK_SCREEN)
    data class UnlockScreen(
        val timeoutMs: Long = 5_000L,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_TOAST)
    data class Toast(
        val text: String,
        val longDuration: Boolean = false,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_OPEN_URL)
    data class OpenUrl(
        val url: String,
        val targetPackage: String? = null,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_COPY_TO_CLIPBOARD)
    data class CopyToClipboard(
        val text: String,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_GET_CLIPBOARD)
    data class GetClipboard(
        val intoVar: String,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_VIBRATE)
    data class Vibrate(
        val durationMs: Long = 250L,
        val amplitude: Int = -1,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_TTS)
    data class Tts(
        val text: String,
        val localeTag: String? = null,
        val queue: TtsQueueMode = TtsQueueMode.ADD,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_MATH)
    data class Math(
        val left: String,
        val op: MathOp,
        val right: String,
        val intoVar: String,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_STRING_TRANSFORM)
    data class StringTransform(
        val input: String,
        val op: StringOp,
        val arg1: String = "",
        val arg2: String = "",
        val intoVar: String,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_DATE_FORMAT)
    data class DateFormat(
        val timestamp: String = "",
        val pattern: String = "yyyy-MM-dd HH:mm:ss",
        val intoVar: String,
        val continueOnError: Boolean = true,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_LOCK_SCREEN)
    data class LockScreen(
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_SEND_SMS)
    data class SendSms(
        val phoneNumber: String,
        val body: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_SEND_WHATSAPP)
    data class SendWhatsApp(
        val phoneNumber: String,
        val body: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_SEND_TELEGRAM)
    data class SendTelegram(
        val recipient: String,
        val body: String,
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_SEND_EMAIL)
    data class SendEmail(
        val to: String,
        val subject: String = "",
        val body: String = "",
        val cc: String = "",
        val bcc: String = "",
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()

    @Serializable @SerialName(FlowSerialization.TYPE_MQTT_PUBLISH)
    data class MqttPublish(
        val brokerUrl: String,
        val topic: String,
        val payload: String = "",
        val qos: Int = 0,
        val retained: Boolean = false,
        val username: String = "",
        val password: String = "",
        val clientId: String = "",
        val timeoutMs: Long = 10_000L,
        val storeResponseInVar: String = "",
        val continueOnError: Boolean = false,
        val label: String? = null,
    ) : ActionSurrogate()
}

/**
 * Serialisable mirror of [Condition]. Mirrors the domain class field-for-field so the on-disk
 * JSON shape is `{"left":"...","op":"EQUALS","right":"..."}` — stable and human-readable.
 */
@Serializable
internal data class ConditionSurrogate(
    val left: String,
    val op: CompareOp,
    val right: String = "",
)

/**
 * Serialisable mirror of [HttpHeader]. Lives at top level (not nested) so kotlinx.serialization
 * can resolve it without surrogate-specific path quirks.
 */
@Serializable
internal data class HttpHeaderSurrogate(
    val name: String,
    val value: String,
)

internal fun Action.toSurrogate(): ActionSurrogate = when (this) {
    is Action.ClickNotificationAction -> ActionSurrogate.ClickNotificationAction(
        labelRegex = labelRegex,
        dismissAfter = dismissAfter,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.LaunchApp -> ActionSurrogate.LaunchApp(
        packageName = packageName,
        activityClass = activityClass,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.PostNotification -> ActionSurrogate.PostNotification(
        title = title,
        text = text,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.Delay -> ActionSurrogate.Delay(
        millis = millis,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.KillApp -> ActionSurrogate.KillApp(
        packageName = packageName,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.UiClick -> ActionSurrogate.UiClick(
        targetMode = targetMode,
        x = x,
        y = y,
        text = text,
        textMatch = textMatch,
        contentDescription = contentDescription,
        viewId = viewId,
        inPackage = inPackage,
        clickMode = clickMode,
        longPressDurationMs = longPressDurationMs,
        timeoutMs = timeoutMs,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.UiSwipe -> ActionSurrogate.UiSwipe(
        direction = direction,
        fromX = fromX,
        fromY = fromY,
        toX = toX,
        toY = toY,
        durationMs = durationMs,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.UiTypeText -> ActionSurrogate.UiTypeText(
        text = text,
        pressEnterAfter = pressEnterAfter,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.UiPressKey -> ActionSurrogate.UiPressKey(
        key = key,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.Http -> ActionSurrogate.Http(
        method = method,
        url = url,
        headers = headers.map { HttpHeaderSurrogate(it.name, it.value) },
        body = body,
        bodyContentType = bodyContentType,
        timeoutMs = timeoutMs,
        expectStatusMin = expectStatusMin,
        expectStatusMax = expectStatusMax,
        storeStatusInVar = storeStatusInVar,
        storeBodyInVar = storeBodyInVar,
        retries = retries,
        retryBackoffMs = retryBackoffMs,
        retryOnStatusMin = retryOnStatusMin,
        retryOnStatusMax = retryOnStatusMax,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.SetVariable -> ActionSurrogate.SetVariable(
        name = name,
        value = value,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.ReadFile -> ActionSurrogate.ReadFile(
        path = path,
        intoVar = intoVar,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.WriteFile -> ActionSurrogate.WriteFile(
        path = path,
        content = content,
        mode = mode,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.Base64 -> ActionSurrogate.Base64(
        input = input,
        mode = mode,
        intoVar = intoVar,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.Hash -> ActionSurrogate.Hash(
        input = input,
        algorithm = algorithm,
        intoVar = intoVar,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.If -> ActionSurrogate.If(
        condition = ConditionSurrogate(
            left = condition.left,
            op = condition.op,
            right = condition.right,
        ),
        thenActions = thenActions.map { it.toSurrogate() },
        elseActions = elseActions.map { it.toSurrogate() },
        continueOnError = continueOnError,
        label = label,
    )
    is Action.Loop -> ActionSurrogate.Loop(
        mode = mode,
        count = count,
        list = list,
        itemVar = itemVar,
        indexVar = indexVar,
        actions = actions.map { it.toSurrogate() },
        continueOnError = continueOnError,
        label = label,
    )
    is Action.TryCatch -> ActionSurrogate.TryCatch(
        tryActions = tryActions.map { it.toSurrogate() },
        catchActions = catchActions.map { it.toSurrogate() },
        finallyActions = finallyActions.map { it.toSurrogate() },
        intoErrorVar = intoErrorVar,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.UnlockScreen -> ActionSurrogate.UnlockScreen(
        timeoutMs = timeoutMs,
        continueOnError = continueOnError,
        label = label,
    )
    is Action.Toast -> ActionSurrogate.Toast(text = text, longDuration = longDuration, continueOnError = continueOnError, label = label)
    is Action.OpenUrl -> ActionSurrogate.OpenUrl(url = url, targetPackage = targetPackage, continueOnError = continueOnError, label = label)
    is Action.CopyToClipboard -> ActionSurrogate.CopyToClipboard(text = text, continueOnError = continueOnError, label = label)
    is Action.GetClipboard -> ActionSurrogate.GetClipboard(intoVar = intoVar, continueOnError = continueOnError, label = label)
    is Action.Vibrate -> ActionSurrogate.Vibrate(durationMs = durationMs, amplitude = amplitude, continueOnError = continueOnError, label = label)
    is Action.Tts -> ActionSurrogate.Tts(text = text, localeTag = localeTag, queue = queue, continueOnError = continueOnError, label = label)
    is Action.Math -> ActionSurrogate.Math(left = left, op = op, right = right, intoVar = intoVar, continueOnError = continueOnError, label = label)
    is Action.StringTransform -> ActionSurrogate.StringTransform(input = input, op = op, arg1 = arg1, arg2 = arg2, intoVar = intoVar, continueOnError = continueOnError, label = label)
    is Action.DateFormat -> ActionSurrogate.DateFormat(timestamp = timestamp, pattern = pattern, intoVar = intoVar, continueOnError = continueOnError, label = label)
    is Action.LockScreen -> ActionSurrogate.LockScreen(continueOnError = continueOnError, label = label)
    is Action.SendSms -> ActionSurrogate.SendSms(phoneNumber = phoneNumber, body = body, continueOnError = continueOnError, label = label)
    is Action.SendWhatsApp -> ActionSurrogate.SendWhatsApp(phoneNumber = phoneNumber, body = body, continueOnError = continueOnError, label = label)
    is Action.SendTelegram -> ActionSurrogate.SendTelegram(recipient = recipient, body = body, continueOnError = continueOnError, label = label)
    is Action.SendEmail -> ActionSurrogate.SendEmail(to = to, subject = subject, body = body, cc = cc, bcc = bcc, continueOnError = continueOnError, label = label)
    is Action.MqttPublish -> ActionSurrogate.MqttPublish(brokerUrl = brokerUrl, topic = topic, payload = payload, qos = qos, retained = retained, username = username, password = password, clientId = clientId, timeoutMs = timeoutMs, storeResponseInVar = storeResponseInVar, continueOnError = continueOnError, label = label)
    else -> throw IllegalArgumentException("Unknown Action subtype: ${this::class.qualifiedName}")
}

internal fun ActionSurrogate.toDomain(): Action = when (this) {
    is ActionSurrogate.ClickNotificationAction -> Action.ClickNotificationAction(
        labelRegex = labelRegex,
        dismissAfter = dismissAfter,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.LaunchApp -> Action.LaunchApp(
        packageName = packageName,
        activityClass = activityClass,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.PostNotification -> Action.PostNotification(
        title = title,
        text = text,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.Delay -> Action.Delay(
        millis = millis,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.KillApp -> Action.KillApp(
        packageName = packageName,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.UiClick -> Action.UiClick(
        targetMode = targetMode,
        x = x,
        y = y,
        text = text,
        textMatch = textMatch,
        contentDescription = contentDescription,
        viewId = viewId,
        inPackage = inPackage,
        clickMode = clickMode,
        longPressDurationMs = longPressDurationMs,
        timeoutMs = timeoutMs,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.UiSwipe -> Action.UiSwipe(
        direction = direction,
        fromX = fromX,
        fromY = fromY,
        toX = toX,
        toY = toY,
        durationMs = durationMs,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.UiTypeText -> Action.UiTypeText(
        text = text,
        pressEnterAfter = pressEnterAfter,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.UiPressKey -> Action.UiPressKey(
        key = key,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.Http -> Action.Http(
        method = method,
        url = url,
        headers = headers.map { HttpHeader(it.name, it.value) },
        body = body,
        bodyContentType = bodyContentType,
        timeoutMs = timeoutMs,
        expectStatusMin = expectStatusMin,
        expectStatusMax = expectStatusMax,
        storeStatusInVar = storeStatusInVar,
        storeBodyInVar = storeBodyInVar,
        retries = retries,
        retryBackoffMs = retryBackoffMs,
        retryOnStatusMin = retryOnStatusMin,
        retryOnStatusMax = retryOnStatusMax,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.SetVariable -> Action.SetVariable(
        name = name,
        value = value,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.ReadFile -> Action.ReadFile(
        path = path,
        intoVar = intoVar,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.WriteFile -> Action.WriteFile(
        path = path,
        content = content,
        mode = mode,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.Base64 -> Action.Base64(
        input = input,
        mode = mode,
        intoVar = intoVar,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.Hash -> Action.Hash(
        input = input,
        algorithm = algorithm,
        intoVar = intoVar,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.If -> Action.If(
        condition = Condition(
            left = condition.left,
            op = condition.op,
            right = condition.right,
        ),
        thenActions = thenActions.map { it.toDomain() },
        elseActions = elseActions.map { it.toDomain() },
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.Loop -> Action.Loop(
        mode = mode,
        count = count,
        list = list,
        itemVar = itemVar,
        indexVar = indexVar,
        actions = actions.map { it.toDomain() },
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.TryCatch -> Action.TryCatch(
        tryActions = tryActions.map { it.toDomain() },
        catchActions = catchActions.map { it.toDomain() },
        finallyActions = finallyActions.map { it.toDomain() },
        intoErrorVar = intoErrorVar,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.UnlockScreen -> Action.UnlockScreen(
        timeoutMs = timeoutMs,
        continueOnError = continueOnError,
        label = label,
    )
    is ActionSurrogate.Toast -> Action.Toast(text = text, longDuration = longDuration, continueOnError = continueOnError, label = label)
    is ActionSurrogate.OpenUrl -> Action.OpenUrl(url = url, targetPackage = targetPackage, continueOnError = continueOnError, label = label)
    is ActionSurrogate.CopyToClipboard -> Action.CopyToClipboard(text = text, continueOnError = continueOnError, label = label)
    is ActionSurrogate.GetClipboard -> Action.GetClipboard(intoVar = intoVar, continueOnError = continueOnError, label = label)
    is ActionSurrogate.Vibrate -> Action.Vibrate(durationMs = durationMs, amplitude = amplitude, continueOnError = continueOnError, label = label)
    is ActionSurrogate.Tts -> Action.Tts(text = text, localeTag = localeTag, queue = queue, continueOnError = continueOnError, label = label)
    is ActionSurrogate.Math -> Action.Math(left = left, op = op, right = right, intoVar = intoVar, continueOnError = continueOnError, label = label)
    is ActionSurrogate.StringTransform -> Action.StringTransform(input = input, op = op, arg1 = arg1, arg2 = arg2, intoVar = intoVar, continueOnError = continueOnError, label = label)
    is ActionSurrogate.DateFormat -> Action.DateFormat(timestamp = timestamp, pattern = pattern, intoVar = intoVar, continueOnError = continueOnError, label = label)
    is ActionSurrogate.LockScreen -> Action.LockScreen(continueOnError = continueOnError, label = label)
    is ActionSurrogate.SendSms -> Action.SendSms(phoneNumber = phoneNumber, body = body, continueOnError = continueOnError, label = label)
    is ActionSurrogate.SendWhatsApp -> Action.SendWhatsApp(phoneNumber = phoneNumber, body = body, continueOnError = continueOnError, label = label)
    is ActionSurrogate.SendTelegram -> Action.SendTelegram(recipient = recipient, body = body, continueOnError = continueOnError, label = label)
    is ActionSurrogate.SendEmail -> Action.SendEmail(to = to, subject = subject, body = body, cc = cc, bcc = bcc, continueOnError = continueOnError, label = label)
    is ActionSurrogate.MqttPublish -> Action.MqttPublish(brokerUrl = brokerUrl, topic = topic, payload = payload, qos = qos, retained = retained, username = username, password = password, clientId = clientId, timeoutMs = timeoutMs, storeResponseInVar = storeResponseInVar, continueOnError = continueOnError, label = label)
}

/* ---------------------------------------------------------------------------------------------- */
/* Top-level helpers                                                                               */
/* ---------------------------------------------------------------------------------------------- */

/**
 * Serialize a single [Trigger] to its on-disk JSON representation.
 *
 * Output is a JSON object containing a `"type"` discriminator and the trigger's payload, e.g.:
 * ```json
 * {"type":"NotificationPosted","packageName":"com.example.app", ...}
 * ```
 *
 * Retained for backwards compatibility — the [FlowEntity] storage uses
 * [serializeTriggers] / [deserializeTriggers] (JSON array).
 */
internal fun serializeTrigger(trigger: Trigger): String =
    FlowSerialization.json.encodeToString(TriggerSurrogate.serializer(), trigger.toSurrogate())

/**
 * Decode a single [Trigger] from its on-disk JSON. Throws [SerializationException] on:
 *  - malformed JSON,
 *  - unknown `"type"` discriminator (forward-incompatible payload),
 *  - missing required fields.
 *
 * Retained for backwards compatibility; production reads/writes through the list variants.
 */
internal fun deserializeTrigger(serialized: String): Trigger =
    FlowSerialization.json.decodeFromString(TriggerSurrogate.serializer(), serialized).toDomain()

/**
 * Serialize an ordered list of [Trigger]s to a JSON array.
 *
 * This is the on-disk representation for [FlowEntity.triggersJson]. Element order is preserved
 * but is semantically irrelevant: the engine fires on the first match regardless of position.
 */
internal fun serializeTriggers(triggers: List<Trigger>): String =
    FlowSerialization.json.encodeToString(
        ListSerializer(TriggerSurrogate.serializer()),
        triggers.map { it.toSurrogate() },
    )

/**
 * Decode an ordered list of [Trigger]s from a JSON array. Throws [SerializationException] on the
 * same conditions as [deserializeTrigger].
 */
internal fun deserializeTriggers(serialized: String): List<Trigger> =
    FlowSerialization.json
        .decodeFromString(ListSerializer(TriggerSurrogate.serializer()), serialized)
        .map { it.toDomain() }

/**
 * Serialize an ordered list of [Action]s to a JSON array.
 */
internal fun serializeActions(actions: List<Action>): String =
    FlowSerialization.json.encodeToString(
        ListSerializer(ActionSurrogate.serializer()),
        actions.map { it.toSurrogate() },
    )

/**
 * Decode an ordered list of [Action]s. Throws [SerializationException] on the same conditions as
 * [deserializeTrigger].
 */
internal fun deserializeActions(serialized: String): List<Action> =
    FlowSerialization.json
        .decodeFromString(ListSerializer(ActionSurrogate.serializer()), serialized)
        .map { it.toDomain() }
