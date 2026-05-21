package com.flowdroid.ui.flows

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.MathOp
import com.flowdroid.common.flow.StringOp
import com.flowdroid.common.flow.TtsQueueMode

/**
 * Minimal field composables for the Phase-19 (Tasker-parity) actions. Each is intentionally
 * compact — most have 1–3 inputs — so the editor's collapsed/expanded behaviour stays snappy.
 *
 * Heavier helpers (`EnumDropdown`, `OutlinedTextField` decoration, `rememberIntDigitState`,
 * etc.) are declared in [FlowEditorScreen.kt]; this file lives next to it (`internal` visibility)
 * so the helpers are reachable without changing visibility modifiers.
 */

@Composable
internal fun ToastFields(action: Action.Toast, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_toast_text"),
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text("Text (magic-text supported)") },
        minLines = 1,
        maxLines = 3,
    )
    Spacer(Modifier.height(8.dp))
    Row {
        Text("Long duration", modifier = Modifier.weight(1f))
        Switch(
            modifier = Modifier.testTag("action_toast_long"),
            checked = action.longDuration,
            onCheckedChange = { onUpdate(action.copy(longDuration = it)) },
        )
    }
}

@Composable
internal fun OpenUrlFields(action: Action.OpenUrl, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_openurl_url"),
        value = action.url,
        onValueChange = { onUpdate(action.copy(url = it)) },
        label = { Text("URL (magic-text supported)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_openurl_pkg"),
        value = action.targetPackage.orEmpty(),
        onValueChange = { onUpdate(action.copy(targetPackage = it.ifBlank { null })) },
        label = { Text("Target package (optional)") },
        singleLine = true,
        supportingText = { Text("e.g. com.android.chrome — leave blank to use the default browser") },
    )
}

@Composable
internal fun CopyToClipboardFields(action: Action.CopyToClipboard, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_copy_text"),
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text("Text to copy (magic-text supported)") },
        minLines = 2, maxLines = 5,
    )
}

@Composable
internal fun GetClipboardFields(action: Action.GetClipboard, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_getclip_var"),
        value = action.intoVar,
        onValueChange = { onUpdate(action.copy(intoVar = it)) },
        label = { Text("Store clipboard in variable") },
        singleLine = true,
    )
}

@Composable
internal fun VibrateFields(action: Action.Vibrate, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_vibrate_duration"),
        value = action.durationMs.toString(),
        onValueChange = { raw ->
            val ms = raw.filter { it.isDigit() }.toLongOrNull() ?: 0L
            onUpdate(action.copy(durationMs = ms.coerceIn(10L, 10_000L)))
        },
        label = { Text("Duration (ms, 10..10000)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
internal fun TtsFields(action: Action.Tts, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_tts_text"),
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text("Text to speak (magic-text supported)") },
        minLines = 2, maxLines = 5,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_tts_locale"),
        value = action.localeTag.orEmpty(),
        onValueChange = { onUpdate(action.copy(localeTag = it.ifBlank { null })) },
        label = { Text("Locale tag (optional, e.g. en-US, it-IT)") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    Row {
        Text("Flush queue", modifier = Modifier.weight(1f))
        Switch(
            modifier = Modifier.testTag("action_tts_flush"),
            checked = action.queue == TtsQueueMode.FLUSH,
            onCheckedChange = {
                onUpdate(action.copy(queue = if (it) TtsQueueMode.FLUSH else TtsQueueMode.ADD))
            },
        )
    }
}

@Composable
internal fun MathFields(action: Action.Math, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_math_left"),
        value = action.left,
        onValueChange = { onUpdate(action.copy(left = it)) },
        label = { Text("Left operand (magic-text)") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    EnumDropdownLocal(
        tag = "action_math_op",
        label = "Operator",
        values = MathOp.entries.toList(),
        selected = action.op,
        toLabel = {
            when (it) {
                MathOp.ADD -> "+ (add)"
                MathOp.SUBTRACT -> "− (subtract)"
                MathOp.MULTIPLY -> "× (multiply)"
                MathOp.DIVIDE -> "÷ (divide)"
                MathOp.MODULO -> "% (modulo)"
                MathOp.POWER -> "^ (power)"
            }
        },
        onSelect = { onUpdate(action.copy(op = it)) },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_math_right"),
        value = action.right,
        onValueChange = { onUpdate(action.copy(right = it)) },
        label = { Text("Right operand (magic-text)") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_math_var"),
        value = action.intoVar,
        onValueChange = { onUpdate(action.copy(intoVar = it)) },
        label = { Text("Store result in variable") },
        singleLine = true,
    )
}

@Composable
internal fun StringTransformFields(action: Action.StringTransform, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_str_input"),
        value = action.input,
        onValueChange = { onUpdate(action.copy(input = it)) },
        label = { Text("Input (magic-text)") },
        minLines = 1, maxLines = 4,
    )
    Spacer(Modifier.height(8.dp))
    EnumDropdownLocal(
        tag = "action_str_op",
        label = "Transform",
        values = StringOp.entries.toList(),
        selected = action.op,
        toLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        onSelect = { onUpdate(action.copy(op = it)) },
    )
    Spacer(Modifier.height(8.dp))
    if (action.op == StringOp.REPLACE) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("action_str_arg1"),
            value = action.arg1,
            onValueChange = { onUpdate(action.copy(arg1 = it)) },
            label = { Text("Find (literal, not regex)") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("action_str_arg2"),
            value = action.arg2,
            onValueChange = { onUpdate(action.copy(arg2 = it)) },
            label = { Text("Replace with") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
    } else if (action.op == StringOp.SUBSTRING) {
        Row {
            OutlinedTextField(
                modifier = Modifier.weight(1f).testTag("action_str_arg1"),
                value = action.arg1,
                onValueChange = { onUpdate(action.copy(arg1 = it)) },
                label = { Text("Start") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                modifier = Modifier.weight(1f).testTag("action_str_arg2"),
                value = action.arg2,
                onValueChange = { onUpdate(action.copy(arg2 = it)) },
                label = { Text("End") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_str_var"),
        value = action.intoVar,
        onValueChange = { onUpdate(action.copy(intoVar = it)) },
        label = { Text("Store result in variable") },
        singleLine = true,
    )
}

@Composable
internal fun DateFormatFields(action: Action.DateFormat, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_date_ts"),
        value = action.timestamp,
        onValueChange = { onUpdate(action.copy(timestamp = it)) },
        label = { Text("Timestamp (Unix millis; blank = now)") },
        singleLine = true,
        supportingText = { Text("e.g. {trigger.firedAtMillis}, {notification.postTime}") },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_date_pattern"),
        value = action.pattern,
        onValueChange = { onUpdate(action.copy(pattern = it)) },
        label = { Text("SimpleDateFormat pattern") },
        singleLine = true,
        supportingText = { Text("yyyy-MM-dd, HH:mm:ss, EEEE, etc.") },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_date_var"),
        value = action.intoVar,
        onValueChange = { onUpdate(action.copy(intoVar = it)) },
        label = { Text("Store formatted date in variable") },
        singleLine = true,
    )
}

@Composable
internal fun LockScreenFields(action: Action.LockScreen, onUpdate: (Action) -> Unit) {
    Text(
        "Locks the device screen via the accessibility service. Make sure the " +
            "FlowDroid Accessibility service is enabled in Setup.",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SendSmsFields(action: Action.SendSms, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_sms_phone"),
        value = action.phoneNumber,
        onValueChange = { onUpdate(action.copy(phoneNumber = it)) },
        label = { Text("Phone number") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_sms_body"),
        value = action.body,
        onValueChange = { onUpdate(action.copy(body = it)) },
        label = { Text("Message body (magic-text)") },
        minLines = 2, maxLines = 5,
        supportingText = {
            Text("Requires SEND_SMS permission — grant via Settings → Apps → FlowDroid → Permissions → SMS.")
        },
    )
}

@Composable
internal fun SendWhatsAppFields(action: Action.SendWhatsApp, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_wa_phone"),
        value = action.phoneNumber,
        onValueChange = { onUpdate(action.copy(phoneNumber = it)) },
        label = { Text("Phone (international, digits only)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_wa_body"),
        value = action.body,
        onValueChange = { onUpdate(action.copy(body = it)) },
        label = { Text("Message body (magic-text)") },
        minLines = 2, maxLines = 5,
        supportingText = { Text("WhatsApp opens the chat with this pre-filled; you tap Send.") },
    )
}

@Composable
internal fun SendTelegramFields(action: Action.SendTelegram, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_tg_recipient"),
        value = action.recipient,
        onValueChange = { onUpdate(action.copy(recipient = it)) },
        label = { Text("Recipient (@username or phone)") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_tg_body"),
        value = action.body,
        onValueChange = { onUpdate(action.copy(body = it)) },
        label = { Text("Message body (magic-text)") },
        minLines = 2, maxLines = 5,
        supportingText = { Text("Telegram opens the chat with this pre-filled; you tap Send.") },
    )
}

/**
 * Local copy of the enum-dropdown helper from [FlowEditorScreen.kt]. We re-implement here rather
 * than widening the visibility of the original because (a) this file's actions are an additive
 * batch that may move into its own module later, and (b) tightening visibility back later is
 * harder than starting tight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdownLocal(
    tag: String,
    label: String,
    values: List<T>,
    selected: T,
    toLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        modifier = Modifier.testTag(tag),
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            value = toLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            values.forEach { v ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("${tag}_item_${v.toString()}"),
                    text = { Text(toLabel(v)) },
                    onClick = {
                        onSelect(v)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SendEmailFields(action: Action.SendEmail, onUpdate: (Action) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_email_to"),
        value = action.to,
        onValueChange = { onUpdate(action.copy(to = it)) },
        label = { Text("To") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_email_subject"),
        value = action.subject,
        onValueChange = { onUpdate(action.copy(subject = it)) },
        label = { Text("Subject (magic-text)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_email_body"),
        value = action.body,
        onValueChange = { onUpdate(action.copy(body = it)) },
        label = { Text("Body (magic-text)") },
        minLines = 3, maxLines = 8,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_email_cc"),
        value = action.cc,
        onValueChange = { onUpdate(action.copy(cc = it)) },
        label = { Text("Cc (optional, comma-separated)") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_email_bcc"),
        value = action.bcc,
        onValueChange = { onUpdate(action.copy(bcc = it)) },
        label = { Text("Bcc (optional, comma-separated)") },
        singleLine = true,
    )
}
