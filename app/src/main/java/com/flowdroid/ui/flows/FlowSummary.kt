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

/**
 * Pure helpers that translate [Trigger]s and [Action]s into the short human-readable strings
 * shown on the Flows list (and in tests). Kept dependency-free so they're trivial to unit-test
 * off-device.
 */

/**
 * Render a single [Trigger] as a one-line description.
 *  - `NotificationPosted(packageName="com.example.app", titleRegex="^Echo.*")`
 *    -> `Notification from com.example.app where title matches ^Echo.*`
 *  - `NotificationPosted()` -> `Notification from any app`
 */
fun summarizeTrigger(trigger: Trigger): String = when (trigger) {
    is Trigger.NotificationPosted -> summarizeNotificationTrigger(trigger)
    is Trigger.TimeOfDay -> summarizeTimeOfDayTrigger(trigger)
    is Trigger.Interval -> summarizeIntervalTrigger(trigger)
    is Trigger.Webhook -> summarizeWebhookTrigger(trigger)
    is Trigger.MqttSubscribe -> "MQTT ${trigger.topic} @ ${trigger.brokerUrl}"
    else -> "Custom trigger (${trigger::class.simpleName ?: "?"})"
}

private fun summarizeTimeOfDayTrigger(t: Trigger.TimeOfDay): String {
    val time = "%02d:%02d".format(t.hour, t.minute)
    val allDays = t.daysOfWeek.isEmpty() || t.daysOfWeek.size == DayOfWeek.entries.size
    return if (allDays) {
        "Daily at $time"
    } else {
        val days = DayOfWeek.entries.filter { it in t.daysOfWeek }.joinToString(", ") { it.name }
        "Daily at $time (on $days)"
    }
}

private fun summarizeIntervalTrigger(t: Trigger.Interval): String {
    val base = "Every ${t.intervalMinutes}m"
    val w = t.activeWindow ?: return base
    return "$base between ${formatTimeRange(w)}"
}

private fun formatTimeRange(w: TimeRange): String =
    "%02d:%02d–%02d:%02d".format(w.startHour, w.startMinute, w.endHour, w.endMinute)

private fun summarizeWebhookTrigger(t: Trigger.Webhook): String =
    "Webhook /hook/${t.path}"

/**
 * Render a *list* of triggers as a single line, joined with " or ". Empty list returns
 * "(no triggers)" — but in practice the editor enforces at least one trigger.
 */
fun summarizeTriggers(triggers: List<Trigger>): String {
    if (triggers.isEmpty()) return "(no triggers)"
    return triggers.joinToString(separator = " or ") { summarizeTrigger(it) }
}

private fun summarizeNotificationTrigger(t: Trigger.NotificationPosted): String {
    val sb = StringBuilder()
    sb.append("Notification")
    if (t.packageName != null) {
        sb.append(" from ").append(t.packageName)
    } else {
        sb.append(" from any app")
    }
    val filters = buildList {
        t.titleRegex?.let { add("title matches $it") }
        t.textRegex?.let { add("text matches $it") }
        t.actionLabelRegex?.let { add("action label matches $it") }
    }
    if (filters.isNotEmpty()) {
        sb.append(" where ").append(filters.joinToString(" and "))
    }
    return sb.toString()
}

/**
 * Render a list of [Action]s as a semicolon-separated summary, one segment per action.
 * Empty list -> "(no actions)".
 */
fun summarizeActions(actions: List<Action>): String {
    if (actions.isEmpty()) return "(no actions)"
    return actions.joinToString(separator = "; ") { summarizeAction(it) }
}

/** One-shot summary of a single [Action]. */
fun summarizeAction(action: Action): String = when (action) {
    is Action.ClickNotificationAction ->
        "Click action button matching ${action.labelRegex}" +
            (if (action.dismissAfter) " (dismiss after)" else "")
    is Action.LaunchApp ->
        "Launch ${action.packageName}" +
            (action.activityClass?.let { " / $it" }.orEmpty())
    is Action.PostNotification ->
        "Post notification"
    is Action.Delay ->
        "Wait ${formatMillis(action.millis)}"
    is Action.KillApp ->
        "Kill ${action.packageName}"
    is Action.UiClick ->
        summarizeUiClick(action)
    is Action.UiSwipe ->
        summarizeUiSwipe(action)
    is Action.UiTypeText ->
        "Type \"${action.text}\"" + if (action.pressEnterAfter) " ⏎" else ""
    is Action.UiPressKey ->
        "Press ${humanKey(action.key)}"
    is Action.Http -> {
        val short = action.url.removePrefix("https://").removePrefix("http://")
        "HTTP ${action.method} ${short.take(40)}${if (short.length > 40) "…" else ""}"
    }
    is Action.SetVariable ->
        "Set {var.${action.name}} = ${action.value.take(40)}${if (action.value.length > 40) "…" else ""}"
    is Action.ReadFile ->
        "Read file ${action.path} → {var.${action.intoVar}}"
    is Action.WriteFile -> {
        val verb = when (action.mode) {
            com.flowdroid.common.flow.FileWriteMode.OVERWRITE -> "Write"
            com.flowdroid.common.flow.FileWriteMode.APPEND -> "Append to"
        }
        "$verb file ${action.path}"
    }
    is Action.Base64 -> {
        val verb = when (action.mode) {
            com.flowdroid.common.flow.Base64Mode.ENCODE -> "Base64 encode"
            com.flowdroid.common.flow.Base64Mode.DECODE -> "Base64 decode"
        }
        "$verb → {var.${action.intoVar}}"
    }
    is Action.Hash ->
        "${action.algorithm.name} hash → {var.${action.intoVar}}"
    is Action.If -> summarizeIf(action)
    is Action.Loop -> summarizeLoop(action)
    is Action.TryCatch -> {
        val t = action.tryActions.size
        val c = action.catchActions.size
        val f = action.finallyActions.size
        buildString {
            append("Try $t")
            if (c > 0) append(" · catch $c")
            if (f > 0) append(" · finally $f")
        }
    }
    is Action.UnlockScreen ->
        "Unlock screen (timeout ${action.timeoutMs}ms)"
    is Action.LockScreen -> "Lock screen"
    is Action.Toast -> "Toast \"${action.text.take(40)}${if (action.text.length > 40) "…" else ""}\""
    is Action.OpenUrl -> "Open ${action.url.take(50)}${if (action.url.length > 50) "…" else ""}"
    is Action.CopyToClipboard -> "Copy to clipboard: \"${action.text.take(30)}…\""
    is Action.GetClipboard -> "Read clipboard → {var.${action.intoVar}}"
    is Action.Vibrate -> "Vibrate ${action.durationMs}ms"
    is Action.Tts -> "Speak \"${action.text.take(40)}${if (action.text.length > 40) "…" else ""}\""
    is Action.Math -> {
        val sym = when (action.op) {
            com.flowdroid.common.flow.MathOp.ADD -> "+"
            com.flowdroid.common.flow.MathOp.SUBTRACT -> "−"
            com.flowdroid.common.flow.MathOp.MULTIPLY -> "×"
            com.flowdroid.common.flow.MathOp.DIVIDE -> "÷"
            com.flowdroid.common.flow.MathOp.MODULO -> "%"
            com.flowdroid.common.flow.MathOp.POWER -> "^"
        }
        "Math: ${action.left} $sym ${action.right} → {var.${action.intoVar}}"
    }
    is Action.StringTransform ->
        "String ${action.op.name.lowercase()} → {var.${action.intoVar}}"
    is Action.DateFormat ->
        "Date ${action.pattern} → {var.${action.intoVar}}"
    is Action.SendSms -> "SMS to ${action.phoneNumber.take(20)}"
    is Action.SendWhatsApp -> "WhatsApp to ${action.phoneNumber.take(20)}"
    is Action.SendTelegram -> "Telegram to ${action.recipient.take(20)}"
    is Action.SendEmail -> "Email to ${action.to.take(30)}"
    is Action.MqttPublish -> "MQTT publish ${action.topic.take(30)}"
    else -> "Custom action (${action::class.simpleName ?: "?"})"
}

private fun summarizeIf(a: Action.If): String {
    val opLabel = when (a.condition.op) {
        com.flowdroid.common.flow.CompareOp.EQUALS -> "=="
        com.flowdroid.common.flow.CompareOp.NOT_EQUALS -> "!="
        com.flowdroid.common.flow.CompareOp.CONTAINS -> "contains"
        com.flowdroid.common.flow.CompareOp.STARTS_WITH -> "starts-with"
        com.flowdroid.common.flow.CompareOp.ENDS_WITH -> "ends-with"
        com.flowdroid.common.flow.CompareOp.REGEX -> "matches"
        com.flowdroid.common.flow.CompareOp.GT -> ">"
        com.flowdroid.common.flow.CompareOp.LT -> "<"
        com.flowdroid.common.flow.CompareOp.GTE -> ">="
        com.flowdroid.common.flow.CompareOp.LTE -> "<="
        com.flowdroid.common.flow.CompareOp.IS_BLANK -> "is blank"
        com.flowdroid.common.flow.CompareOp.IS_NOT_BLANK -> "is not blank"
    }
    val unary = a.condition.op == com.flowdroid.common.flow.CompareOp.IS_BLANK ||
        a.condition.op == com.flowdroid.common.flow.CompareOp.IS_NOT_BLANK
    val cond = if (unary) {
        "${a.condition.left.takeOrElse("?")} $opLabel"
    } else {
        "${a.condition.left.takeOrElse("?")} $opLabel ${a.condition.right.takeOrElse("?")}"
    }
    val thenN = a.thenActions.size
    val elseN = a.elseActions.size
    return buildString {
        append("If ")
        append(cond)
        append(" then $thenN")
        if (elseN > 0) append(" else $elseN")
    }
}

private fun String.takeOrElse(fallback: String): String = ifBlank { fallback }

private fun summarizeLoop(a: Action.Loop): String {
    val header = when (a.mode) {
        com.flowdroid.common.flow.LoopMode.COUNT -> "Loop ${a.count}×"
        com.flowdroid.common.flow.LoopMode.FOREACH_LINES -> {
            val src = a.list.ifBlank { "?" }.take(30)
            "Foreach line of \"$src\""
        }
    }
    return "$header · ${a.actions.size} action${if (a.actions.size == 1) "" else "s"}"
}

private fun summarizeUiClick(a: Action.UiClick): String {
    val verb = when (a.clickMode) {
        ClickMode.SINGLE -> "Tap"
        ClickMode.DOUBLE -> "Double-tap"
        ClickMode.LONG -> "Long-press"
    }
    val target = when (a.targetMode) {
        UiTargetMode.COORDINATES -> "at (${trimNum(a.x)}, ${trimNum(a.y)})"
        UiTargetMode.BY_TEXT -> "text \"${a.text.orEmpty()}\"" +
            when (a.textMatch) {
                TextMatch.EXACT -> ""
                TextMatch.CONTAINS -> " (contains)"
                TextMatch.REGEX -> " (regex)"
                TextMatch.STARTS_WITH -> " (starts-with)"
            }
        UiTargetMode.BY_CONTENT_DESCRIPTION -> "desc \"${a.contentDescription.orEmpty()}\""
        UiTargetMode.BY_VIEW_ID -> "id ${a.viewId.orEmpty()}"
    }
    val suffix = a.inPackage?.let { " in $it" }.orEmpty()
    return "$verb $target$suffix"
}

private fun summarizeUiSwipe(a: Action.UiSwipe): String {
    return if (a.direction != null) {
        "Swipe ${a.direction.name.lowercase()}"
    } else {
        "Swipe (${trimNum(a.fromX)},${trimNum(a.fromY)})→(${trimNum(a.toX)},${trimNum(a.toY)})"
    }
}

private fun humanKey(k: UiKey): String = when (k) {
    UiKey.BACK -> "Back"
    UiKey.HOME -> "Home"
    UiKey.RECENTS -> "Recents"
    UiKey.NOTIFICATIONS -> "Notifications"
    UiKey.QUICK_SETTINGS -> "Quick settings"
    UiKey.ENTER -> "Enter"
    UiKey.POWER_DIALOG -> "Power dialog"
    UiKey.LOCK_SCREEN -> "Lock screen"
}

private fun trimNum(v: Float): String {
    val asLong = v.toLong()
    return if (asLong.toFloat() == v) asLong.toString() else v.toString()
}

private fun formatMillis(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val secs = ms / 1000.0
    val rounded = if (secs % 1.0 == 0.0) {
        secs.toLong().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%.1f", secs)
    }
    return "${rounded}s"
}
