package com.flowdroid.ui.flows

import androidx.compose.ui.graphics.Color
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Trigger
import com.flowdroid.ui.theme.FamilyApps
import com.flowdroid.ui.theme.FamilyData
import com.flowdroid.ui.theme.FamilyLogic
import com.flowdroid.ui.theme.FamilyNetwork
import com.flowdroid.ui.theme.FamilyNotifications
import com.flowdroid.ui.theme.FamilyTiming
import com.flowdroid.ui.theme.FamilyUi
import com.flowdroid.ui.theme.FamilyVariables

/**
 * Maps an [Action] subtype to its family-color and a compact emoji glyph for the editor's
 * left-stripe + colored-node decoration. The mapping mirrors [ActionCatalog.ActionFamily]
 * exactly — adding a new action means adding one branch here and one entry to the catalog.
 */
data class ActionVisuals(val color: Color, val glyph: String)

fun visualsFor(action: Action): ActionVisuals = when (action) {
    is Action.ClickNotificationAction, is Action.PostNotification, is Action.Toast, is Action.Tts ->
        ActionVisuals(FamilyNotifications, "🔔")
    is Action.LaunchApp, is Action.KillApp, is Action.OpenUrl ->
        ActionVisuals(FamilyApps, "📱")
    is Action.UiClick, is Action.UiSwipe, is Action.UiTypeText, is Action.UiPressKey,
    is Action.UnlockScreen, is Action.LockScreen, is Action.Vibrate ->
        ActionVisuals(FamilyUi, "☝")
    is Action.Delay ->
        ActionVisuals(FamilyTiming, "⏱")
    is Action.Http, is Action.SendSms, is Action.SendWhatsApp, is Action.SendTelegram,
    is Action.SendEmail, is Action.MqttPublish ->
        ActionVisuals(FamilyNetwork, "🌐")
    is Action.SetVariable, is Action.Math, is Action.StringTransform, is Action.DateFormat ->
        ActionVisuals(FamilyVariables, "𝑥")
    is Action.ReadFile, is Action.WriteFile, is Action.Base64, is Action.Hash,
    is Action.CopyToClipboard, is Action.GetClipboard ->
        ActionVisuals(FamilyData, "🗎")
    is Action.If, is Action.Loop, is Action.TryCatch ->
        ActionVisuals(FamilyLogic, "⌥")
    else -> ActionVisuals(FamilyLogic, "·")
}

/** Same idea for triggers — they get a colored node at the top of the rail. */
fun visualsFor(trigger: Trigger): ActionVisuals = when (trigger) {
    is Trigger.NotificationPosted -> ActionVisuals(FamilyNotifications, "🔔")
    is Trigger.TimeOfDay -> ActionVisuals(FamilyTiming, "⏱")
    is Trigger.Interval -> ActionVisuals(FamilyTiming, "↻")
    is Trigger.Webhook -> ActionVisuals(FamilyNetwork, "🌐")
    is Trigger.MqttSubscribe -> ActionVisuals(FamilyNetwork, "⇆")
    else -> ActionVisuals(FamilyLogic, "·")
}

fun colorFor(family: ActionCatalog.ActionFamily): Color = when (family) {
    ActionCatalog.ActionFamily.NOTIFICATIONS -> FamilyNotifications
    ActionCatalog.ActionFamily.APPS -> FamilyApps
    ActionCatalog.ActionFamily.UI_INTERACTION -> FamilyUi
    ActionCatalog.ActionFamily.TIMING -> FamilyTiming
    ActionCatalog.ActionFamily.NETWORK -> FamilyNetwork
    ActionCatalog.ActionFamily.VARIABLES -> FamilyVariables
    ActionCatalog.ActionFamily.DATA -> FamilyData
    ActionCatalog.ActionFamily.LOGIC -> FamilyLogic
}

fun glyphFor(family: ActionCatalog.ActionFamily): String = when (family) {
    ActionCatalog.ActionFamily.NOTIFICATIONS -> "🔔"
    ActionCatalog.ActionFamily.APPS -> "📱"
    ActionCatalog.ActionFamily.UI_INTERACTION -> "☝"
    ActionCatalog.ActionFamily.TIMING -> "⏱"
    ActionCatalog.ActionFamily.NETWORK -> "🌐"
    ActionCatalog.ActionFamily.VARIABLES -> "𝑥"
    ActionCatalog.ActionFamily.DATA -> "🗎"
    ActionCatalog.ActionFamily.LOGIC -> "⌥"
}
