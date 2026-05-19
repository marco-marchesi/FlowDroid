package com.flowdroid.ui.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowdroid.common.permission.DeepLinkTarget
import com.flowdroid.common.permission.OemBrand
import com.flowdroid.common.permission.OemSpecific
import com.flowdroid.common.permission.PermissionSnapshot
import com.flowdroid.ui.theme.FlowDroidTheme
import com.flowdroid.ui.theme.StatusAmber
import com.flowdroid.ui.theme.StatusGreen
import com.flowdroid.ui.theme.StatusRed
import com.flowdroid.ui.theme.StatusUnknown
import kotlinx.coroutines.launch

private enum class StepStatus { GRANTED, NEEDED, OPTIONAL_UNGRANTED, NOT_APPLICABLE }

private data class WizardStep(
    val id: String,
    val title: String,
    val description: String,
    val status: StepStatus,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val secondaryActionLabel: String? = null,
    val onSecondaryAction: (() -> Unit)? = null,
    val testHint: String? = null,
)

@Composable
fun SetupRoute(viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh on resume — the user often returns from Settings and we want the cards to update.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var testResult by remember { mutableStateOf<SelfTestResult?>(null) }

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> viewModel.refresh() }

    SetupScreen(
        state = state,
        testResult = testResult,
        onRefresh = viewModel::refresh,
        onRequestPostNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else viewModel.refresh()
        },
        onOpenListenerSettings = {
            tryStart(ctx, viewModel.resolveDeepLink(DeepLinkTarget.NOTIFICATION_LISTENER_SETTINGS)
                ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        onRunSelfTest = {
            scope.launch {
                viewModel.runSelfTest().collect { result ->
                    testResult = result
                    when (result) {
                        is SelfTestResult.Success -> Toast.makeText(ctx, "Listener received the test notification.", Toast.LENGTH_SHORT).show()
                        is SelfTestResult.TimeoutOrNotReceived -> Toast.makeText(ctx, "Listener did not echo the notification within 3s.", Toast.LENGTH_LONG).show()
                        is SelfTestResult.PostFailed -> Toast.makeText(ctx, "Couldn't post test: ${result.reason}", Toast.LENGTH_LONG).show()
                        SelfTestResult.InProgress -> Unit
                    }
                }
            }
        },
        onOpenBattery = {
            tryStart(ctx, viewModel.resolveDeepLink(DeepLinkTarget.BATTERY_OPTIMISATION))
        },
        onOpenOemNeverSleeping = {
            tryStart(ctx, viewModel.resolveDeepLink(DeepLinkTarget.OEM_NEVER_SLEEPING_APPS))
        },
        onOpenOemAutostart = {
            tryStart(ctx, viewModel.resolveDeepLink(DeepLinkTarget.OEM_AUTOSTART))
        },
        onOpenExactAlarm = {
            tryStart(ctx, viewModel.resolveDeepLink(DeepLinkTarget.EXACT_ALARM_PERMISSION))
        },
        onOpenAccessibility = {
            tryStart(ctx, viewModel.resolveDeepLink(DeepLinkTarget.ACCESSIBILITY_SETTINGS)
                ?: Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
    )
}

private fun tryStart(ctx: Context, intent: Intent?) {
    if (intent == null) {
        Toast.makeText(ctx, "No deep link available on this device.", Toast.LENGTH_SHORT).show()
        return
    }
    val toFire = if (ctx is Activity) intent else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(toFire)
    } catch (t: Throwable) {
        if (t is OutOfMemoryError) throw t
        Toast.makeText(ctx, "Couldn't open Settings: ${t.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun SetupScreen(
    state: SetupUiState,
    testResult: SelfTestResult?,
    onRefresh: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onRunSelfTest: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenOemNeverSleeping: () -> Unit,
    onOpenOemAutostart: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().testTag("setup_screen")) {
        when (state) {
            is SetupUiState.Loading -> CenterSpinner("Reading permissions…")
            is SetupUiState.Error -> SetupErrorBanner(state.message, onRefresh)
            is SetupUiState.Ready -> ReadySetup(
                snapshot = state.snapshot,
                brand = state.brand,
                testResult = testResult,
                onRequestPostNotifications = onRequestPostNotifications,
                onOpenListenerSettings = onOpenListenerSettings,
                onRunSelfTest = onRunSelfTest,
                onOpenBattery = onOpenBattery,
                onOpenOemNeverSleeping = onOpenOemNeverSleeping,
                onOpenOemAutostart = onOpenOemAutostart,
                onOpenExactAlarm = onOpenExactAlarm,
                onOpenAccessibility = onOpenAccessibility,
            )
        }
    }
}

@Composable
private fun ReadySetup(
    snapshot: PermissionSnapshot,
    brand: OemBrand,
    testResult: SelfTestResult?,
    onRequestPostNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onRunSelfTest: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenOemNeverSleeping: () -> Unit,
    onOpenOemAutostart: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val steps = buildSteps(
        snapshot = snapshot,
        brand = brand,
        testResult = testResult,
        onRequestPostNotifications = onRequestPostNotifications,
        onOpenListenerSettings = onOpenListenerSettings,
        onRunSelfTest = onRunSelfTest,
        onOpenBattery = onOpenBattery,
        onOpenOemNeverSleeping = onOpenOemNeverSleeping,
        onOpenOemAutostart = onOpenOemAutostart,
        onOpenExactAlarm = onOpenExactAlarm,
        onOpenAccessibility = onOpenAccessibility,
    )
    val remaining = steps.count { it.status == StepStatus.NEEDED }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummaryBanner(remaining = remaining, total = steps.count { it.status != StepStatus.NOT_APPLICABLE }) }
        items(items = steps, key = { it.id }) { step -> StepCard(step) }
    }
}

@Composable
private fun SummaryBanner(remaining: Int, total: Int) {
    val (container, content) = when {
        remaining == 0 -> StatusGreen.copy(alpha = 0.18f) to "Setup complete — all required steps green."
        remaining == 1 -> StatusAmber.copy(alpha = 0.18f) to "$remaining step left out of $total."
        else -> StatusAmber.copy(alpha = 0.18f) to "$remaining steps left out of $total."
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("setup_summary"),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (remaining == 0) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (remaining == 0) StatusGreen else StatusAmber,
            )
            Spacer(Modifier.size(12.dp))
            Text(content, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepCard(step: WizardStep) {
    val isDisabled = step.status == StepStatus.NOT_APPLICABLE
    Card(
        modifier = Modifier.fillMaxWidth().testTag("setup_step_${step.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDisabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepIcon(step.status)
                Spacer(Modifier.size(12.dp))
                Text(step.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                step.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            step.testHint?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isDisabled && (step.actionLabel != null || step.secondaryActionLabel != null)) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    step.actionLabel?.let { label ->
                        Button(
                            modifier = Modifier.testTag("setup_step_${step.id}_action"),
                            onClick = { step.onAction?.invoke() },
                            enabled = step.onAction != null,
                        ) { Text(label) }
                    }
                    step.secondaryActionLabel?.let { label ->
                        OutlinedButton(
                            modifier = Modifier.testTag("setup_step_${step.id}_secondary"),
                            onClick = { step.onSecondaryAction?.invoke() },
                            enabled = step.onSecondaryAction != null,
                        ) { Text(label) }
                    }
                }
            }
            if (isDisabled) {
                Spacer(Modifier.height(8.dp))
                AssistChip(onClick = {}, label = { Text("Phase 1 — not used yet") })
            }
        }
    }
}

@Composable
private fun StepIcon(status: StepStatus) {
    val (icon, tint) = when (status) {
        StepStatus.GRANTED -> Icons.Filled.CheckCircle to StatusGreen
        StepStatus.NEEDED -> Icons.Filled.Cancel to StatusRed
        StepStatus.OPTIONAL_UNGRANTED -> Icons.Filled.Warning to StatusAmber
        StepStatus.NOT_APPLICABLE -> Icons.Filled.HelpOutline to StatusUnknown
    }
    Icon(icon, contentDescription = status.name, tint = tint)
}

@Composable
private fun CenterSpinner(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SetupErrorBanner(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("setup_error"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Couldn't read permission state", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

private fun buildSteps(
    snapshot: PermissionSnapshot,
    brand: OemBrand,
    testResult: SelfTestResult?,
    onRequestPostNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onRunSelfTest: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenOemNeverSleeping: () -> Unit,
    onOpenOemAutostart: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onOpenAccessibility: () -> Unit,
): List<WizardStep> {
    val steps = mutableListOf<WizardStep>()

    // 1. POST_NOTIFICATIONS (Android 13+).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        steps += WizardStep(
            id = "post_notifications",
            title = "Post notifications",
            description = "Required so FlowDroid can show its foreground-service indicator and the watchdog re-bind notice.",
            status = if (snapshot.postNotificationsGranted) StepStatus.GRANTED else StepStatus.NEEDED,
            actionLabel = if (snapshot.postNotificationsGranted) null else "Grant permission",
            onAction = if (snapshot.postNotificationsGranted) null else onRequestPostNotifications,
        )
    }

    // 2. Notification listener access.
    val listenerHint = when (testResult) {
        SelfTestResult.Success -> "Self-test: listener received the notification."
        SelfTestResult.TimeoutOrNotReceived -> "Self-test: listener did NOT echo within 3s — try again."
        is SelfTestResult.PostFailed -> "Self-test: ${testResult.reason}"
        SelfTestResult.InProgress -> "Self-test in progress…"
        null -> null
    }
    steps += WizardStep(
        id = "listener",
        title = "Notification listener access",
        description = "The core permission. Without it, FlowDroid can't read incoming notifications.",
        status = if (snapshot.notificationListenerEnabled) StepStatus.GRANTED else StepStatus.NEEDED,
        actionLabel = "Open Settings",
        onAction = onOpenListenerSettings,
        secondaryActionLabel = "Test",
        onSecondaryAction = onRunSelfTest,
        testHint = listenerHint,
    )

    // 3. Battery optimisation.
    steps += WizardStep(
        id = "battery",
        title = "Battery optimisation",
        description = "Exclude FlowDroid from battery optimisation so Doze doesn't suspend the foreground service.",
        status = if (snapshot.batteryOptimisationIgnored) StepStatus.GRANTED else StepStatus.NEEDED,
        actionLabel = if (snapshot.batteryOptimisationIgnored) null else "Open Settings",
        onAction = if (snapshot.batteryOptimisationIgnored) null else onOpenBattery,
    )

    // 4. OEM-specific (only if not GENERIC).
    if (brand != OemBrand.GENERIC) {
        val (title, desc, hint, action) = oemRow(brand, snapshot.oemSpecific)
        steps += WizardStep(
            id = "oem",
            title = title,
            description = desc,
            status = if (oemSatisfied(snapshot.oemSpecific)) StepStatus.GRANTED else StepStatus.OPTIONAL_UNGRANTED,
            actionLabel = "Open Settings",
            onAction = when (action) {
                OemAction.NeverSleeping -> onOpenOemNeverSleeping
                OemAction.Autostart -> onOpenOemAutostart
            },
            testHint = hint,
        )
    }

    // 5. Exact alarms — optional, only on SDK 31+.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        steps += WizardStep(
            id = "exact_alarm",
            title = "Exact alarms (optional)",
            description = "Used in Phase 1 for trigger pre-arming. Phase 0 doesn't need this — left here so you can grant it ahead of time.",
            status = if (snapshot.exactAlarmAllowed) StepStatus.GRANTED else StepStatus.OPTIONAL_UNGRANTED,
            actionLabel = if (snapshot.exactAlarmAllowed) null else "Open Settings",
            onAction = if (snapshot.exactAlarmAllowed) null else onOpenExactAlarm,
        )
    }

    // 6. Accessibility — Phase 2 introduces UI tap/swipe/type actions that depend on this.
    steps += WizardStep(
        id = "accessibility",
        title = "Accessibility service",
        description = "Required for UI tap/swipe/type actions. Without this, only notification-action clicks work.",
        status = if (snapshot.accessibilityServiceEnabled) StepStatus.GRANTED else StepStatus.NEEDED,
        actionLabel = if (snapshot.accessibilityServiceEnabled) null else "Open Settings",
        onAction = if (snapshot.accessibilityServiceEnabled) null else onOpenAccessibility,
    )

    return steps
}

private enum class OemAction { NeverSleeping, Autostart }

private data class OemRow(val title: String, val description: String, val hint: String, val action: OemAction)

private fun oemRow(brand: OemBrand, oem: OemSpecific): OemRow = when (brand) {
    OemBrand.SAMSUNG -> OemRow(
        title = "Samsung 'Never sleeping apps'",
        description = "Samsung's Device Care can kill background apps. Add FlowDroid to 'Never sleeping apps' to prevent this.",
        hint = "Find FlowDroid in the list and toggle it ON.",
        action = OemAction.NeverSleeping,
    )
    OemBrand.XIAOMI -> OemRow(
        title = "Xiaomi autostart",
        description = "MIUI blocks autostart by default. Enable it so the watchdog can restart the service after reboot.",
        hint = "Find FlowDroid and enable Autostart.",
        action = OemAction.Autostart,
    )
    OemBrand.OPPO, OemBrand.REALME -> OemRow(
        title = "${brand.name.lowercase().replaceFirstChar { it.uppercase() }} autostart",
        description = "ColorOS/Realme UI restricts background activity by default. Enable autostart and disable background freeze.",
        hint = "Toggle Autostart ON; turn Background freeze OFF.",
        action = OemAction.Autostart,
    )
    OemBrand.ONEPLUS -> OemRow(
        title = "OnePlus deep optimisation",
        description = "OxygenOS Deep Optimisation can suspend foreground services. Disable it for FlowDroid.",
        hint = "Open Battery settings → App battery management → FlowDroid → set to 'Don't optimise'.",
        action = OemAction.NeverSleeping,
    )
    OemBrand.HUAWEI, OemBrand.VIVO -> OemRow(
        title = "${brand.name.lowercase().replaceFirstChar { it.uppercase() }} autostart",
        description = "${brand.name} EMUI/FuntouchOS restricts background activity aggressively.",
        hint = "Find FlowDroid in the autostart list and enable it.",
        action = OemAction.Autostart,
    )
    OemBrand.GENERIC -> OemRow("", "", "", OemAction.Autostart) // not used
}

private fun oemSatisfied(oem: OemSpecific): Boolean = when (oem) {
    OemSpecific.None -> true
    is OemSpecific.Samsung -> oem.deviceCareConfigured && oem.backgroundUsageNotLimited
    is OemSpecific.Xiaomi -> oem.autostartConfigured && oem.batterySaverConfigured
    is OemSpecific.Oppo -> oem.autostartConfigured && oem.backgroundFreezeDisabled
    is OemSpecific.Realme -> oem.autostartConfigured
    is OemSpecific.OnePlus -> oem.deepOptimisationDisabled
}

// --- Previews -----------------------------------------------------------------

private val FakeSnapshot = PermissionSnapshot(
    notificationListenerEnabled = false,
    accessibilityServiceEnabled = false,
    postNotificationsGranted = true,
    batteryOptimisationIgnored = false,
    exactAlarmAllowed = false,
    overlayPermissionGranted = false,
    oemSpecific = OemSpecific.Samsung(false, false),
)

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun SetupReadyPreview() {
    FlowDroidTheme {
        SetupScreen(
            state = SetupUiState.Ready(FakeSnapshot, OemBrand.SAMSUNG),
            testResult = null,
            onRefresh = {},
            onRequestPostNotifications = {},
            onOpenListenerSettings = {},
            onRunSelfTest = {},
            onOpenBattery = {},
            onOpenOemNeverSleeping = {},
            onOpenOemAutostart = {},
            onOpenExactAlarm = {},
            onOpenAccessibility = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun SetupLoadingPreview() {
    FlowDroidTheme {
        SetupScreen(
            state = SetupUiState.Loading,
            testResult = null,
            onRefresh = {},
            onRequestPostNotifications = {},
            onOpenListenerSettings = {},
            onRunSelfTest = {},
            onOpenBattery = {},
            onOpenOemNeverSleeping = {},
            onOpenOemAutostart = {},
            onOpenExactAlarm = {},
            onOpenAccessibility = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun SetupErrorPreview() {
    FlowDroidTheme {
        SetupScreen(
            state = SetupUiState.Error("Permission service unavailable"),
            testResult = null,
            onRefresh = {},
            onRequestPostNotifications = {},
            onOpenListenerSettings = {},
            onRunSelfTest = {},
            onOpenBattery = {},
            onOpenOemNeverSleeping = {},
            onOpenOemAutostart = {},
            onOpenExactAlarm = {},
            onOpenAccessibility = {},
        )
    }
}
