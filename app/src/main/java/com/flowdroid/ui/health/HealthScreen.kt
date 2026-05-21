package com.flowdroid.ui.health

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.ServiceState
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.ui.formatDuration
import com.flowdroid.ui.formatRelative
import com.flowdroid.ui.theme.FlowDroidTheme
import com.flowdroid.ui.theme.StatusAmber
import com.flowdroid.ui.theme.StatusGreen
import com.flowdroid.ui.theme.StatusRed
import com.flowdroid.ui.theme.StatusUnknown

@Composable
fun HealthRoute(viewModel: HealthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HealthScreenEvent.SelfTestRequested ->
                    Toast.makeText(ctx, "Self-test coming soon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    HealthScreen(state = state, onSelfTest = viewModel::runSelfTest)
}

@Composable
internal fun HealthScreen(
    state: HealthUiState,
    onSelfTest: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().testTag("health_screen")) {
        when (state) {
            is HealthUiState.Loading -> LoadingContent()
            is HealthUiState.Error -> ErrorContent(state.message)
            is HealthUiState.Ready -> ReadyContent(state, onSelfTest)
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Reading service state…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("health_error_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.size(8.dp))
                    Text("Couldn't read health state", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: HealthUiState.Ready, onSelfTest: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { OverallStatusCard(state.snapshot, state.nowMillis) }
        item { ServiceRowsCard(state.snapshot, state.nowMillis) }
        item { StatsCard(state) }
        item { DisconnectJournalHeader(state.disconnects.size) }
        items(state.disconnects, key = { it.id }) { evt ->
            DisconnectRow(evt, state.nowMillis)
        }
        if (state.disconnects.isEmpty()) {
            item {
                Text(
                    "No listener disconnects in the recent journal.",
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("health_no_disconnects"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Run-self-test button removed — was not reliably functioning on production builds.
        // (Re-add once the test wiring is fixed; see notes in SetupViewModel.runSelfTest.)
    }
}

@Composable
private fun OverallStatusCard(snapshot: HealthSnapshot, nowMillis: Long) {
    val (color, label, message) = describeOverall(snapshot, nowMillis)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("health_status_card"),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.18f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = overallStatusIcon(snapshot.overallStatus),
                    contentDescription = label,
                    tint = Color.White,
                )
            }
            Spacer(Modifier.size(16.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun overallStatusIcon(status: HealthSnapshot.OverallStatus): ImageVector = when (status) {
    HealthSnapshot.OverallStatus.GREEN -> Icons.Filled.CheckCircle
    HealthSnapshot.OverallStatus.AMBER -> Icons.Filled.Warning
    HealthSnapshot.OverallStatus.RED -> Icons.Filled.Cancel
    HealthSnapshot.OverallStatus.UNKNOWN -> Icons.Filled.HelpOutline
}

private fun describeOverall(snapshot: HealthSnapshot, nowMillis: Long): Triple<Color, String, String> {
    val color = when (snapshot.overallStatus) {
        HealthSnapshot.OverallStatus.GREEN -> StatusGreen
        HealthSnapshot.OverallStatus.AMBER -> StatusAmber
        HealthSnapshot.OverallStatus.RED -> StatusRed
        HealthSnapshot.OverallStatus.UNKNOWN -> StatusUnknown
    }
    val label = when (snapshot.overallStatus) {
        HealthSnapshot.OverallStatus.GREEN -> "All systems green"
        HealthSnapshot.OverallStatus.AMBER -> "Degraded"
        HealthSnapshot.OverallStatus.RED -> "Not running"
        HealthSnapshot.OverallStatus.UNKNOWN -> "Status unknown"
    }
    val message = when {
        snapshot.overallStatus == HealthSnapshot.OverallStatus.GREEN ->
            "Foreground service & listener up. ${snapshot.notificationsReceivedLast24h} notifications today."
        snapshot.notificationListenerState != ServiceState.RUNNING && snapshot.lastListenerDisconnectMillis != null ->
            "Listener disconnected ${formatRelative(snapshot.lastListenerDisconnectMillis!!, nowMillis)}."
        snapshot.foregroundServiceState != ServiceState.RUNNING ->
            "Foreground service is ${snapshot.foregroundServiceState.label()}."
        else -> "Tap a row below for details."
    }
    return Triple(color, label, message)
}

@Composable
private fun ServiceRowsCard(snapshot: HealthSnapshot, nowMillis: Long) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("health_services_card"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            ServiceRow(
                label = "Foreground service",
                state = snapshot.foregroundServiceState,
                uptimeMillis = snapshot.foregroundServiceUptimeMillis.takeIf { it > 0 && snapshot.foregroundServiceState == ServiceState.RUNNING },
                tag = "health_row_foreground",
            )
            HorizontalDivider()
            ServiceRow(
                label = "Notification listener",
                state = snapshot.notificationListenerState,
                uptimeMillis = snapshot.notificationsBoundSinceMillis?.let { nowMillis - it }
                    ?.takeIf { snapshot.notificationListenerState == ServiceState.RUNNING },
                tag = "health_row_listener",
            )
            HorizontalDivider()
            ServiceRow(
                label = "Accessibility service",
                state = snapshot.accessibilityServiceState,
                uptimeMillis = null,
                tag = "health_row_accessibility",
            )
        }
    }
}

@Composable
private fun ServiceRow(
    label: String,
    state: ServiceState,
    uptimeMillis: Long?,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(state.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (uptimeMillis != null) {
            Text(
                "up ${formatDuration(uptimeMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(state: ServiceState) {
    val color = when (state) {
        ServiceState.RUNNING -> StatusGreen
        ServiceState.STARTING -> StatusAmber
        ServiceState.DEGRADED -> StatusAmber
        ServiceState.STOPPED, ServiceState.DISABLED, ServiceState.PERMISSION_MISSING -> StatusRed
        ServiceState.UNKNOWN -> StatusUnknown
    }
    Box(
        modifier = Modifier.size(28.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (state) {
            ServiceState.RUNNING -> Icons.Filled.CheckCircle
            ServiceState.STARTING -> Icons.Filled.HourglassEmpty
            ServiceState.DEGRADED -> Icons.Filled.Warning
            ServiceState.STOPPED, ServiceState.DISABLED, ServiceState.PERMISSION_MISSING -> Icons.Filled.Cancel
            ServiceState.UNKNOWN -> Icons.Filled.HelpOutline
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

private fun ServiceState.label(): String = when (this) {
    ServiceState.RUNNING -> "Running"
    ServiceState.STARTING -> "Starting"
    ServiceState.DEGRADED -> "Degraded"
    ServiceState.STOPPED -> "Stopped"
    ServiceState.DISABLED -> "Disabled by user"
    ServiceState.PERMISSION_MISSING -> "Permission missing"
    ServiceState.UNKNOWN -> "Unknown"
}

@Composable
private fun StatsCard(state: HealthUiState.Ready) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("health_stats_card"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Last 24h", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatPill("Notifications", state.notificationsReceivedLast24h.toString(), "stat_notifs")
                StatPill("Rebinds", state.rebindAttemptsLast24h.toString(), "stat_rebinds")
                StatPill("Disconnects", state.listenerDisconnectsLast24h.toString(), "stat_disconnects")
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, tag: String) {
    Column(
        modifier = Modifier.testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DisconnectJournalHeader(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Disconnect journal",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                "$count recent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisconnectRow(event: HealthEvent, nowMillis: Long) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("disconnect_row_${event.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = StatusAmber,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.message, style = MaterialTheme.typography.bodyMedium)
                event.outcome?.let {
                    Text("outcome: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                formatRelative(event.timestampMillis, nowMillis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Previews -----------------------------------------------------------------

private fun fakeSnapshot(status: HealthSnapshot.OverallStatus) = HealthSnapshot(
    foregroundServiceState = if (status == HealthSnapshot.OverallStatus.GREEN) ServiceState.RUNNING else ServiceState.DEGRADED,
    notificationListenerState = if (status == HealthSnapshot.OverallStatus.RED) ServiceState.STOPPED else ServiceState.RUNNING,
    accessibilityServiceState = ServiceState.PERMISSION_MISSING,
    notificationsBoundSinceMillis = 1_700_000_000_000L,
    foregroundServiceUptimeMillis = 8_400_000L,
    lastListenerDisconnectMillis = 1_700_000_000_000L - 4 * 60_000L,
    rebindAttemptsLast24h = 2,
    notificationsReceivedLast24h = 137,
    overallStatus = status,
)

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HealthScreenReadyPreview() {
    FlowDroidTheme {
        HealthScreen(
            state = HealthUiState.Ready(
                snapshot = fakeSnapshot(HealthSnapshot.OverallStatus.GREEN),
                disconnects = listOf(
                    HealthEvent(1, 1_700_000_000_000L - 60_000L, HealthEvent.Kind.LISTENER_DISCONNECTED, "Listener disconnected", "REBIND_REQUESTED"),
                    HealthEvent(2, 1_700_000_000_000L - 2 * 3_600_000L, HealthEvent.Kind.LISTENER_DISCONNECTED, "Listener disconnected", "REBIND_REQUESTED"),
                ),
                rebindAttemptsLast24h = 2,
                listenerDisconnectsLast24h = 2,
                notificationsReceivedLast24h = 137,
                rebindsFromJournal = 2,
                nowMillis = 1_700_000_000_000L,
            ),
            onSelfTest = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HealthScreenLoadingPreview() {
    FlowDroidTheme { HealthScreen(state = HealthUiState.Loading, onSelfTest = {}) }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HealthScreenErrorPreview() {
    FlowDroidTheme { HealthScreen(state = HealthUiState.Error("Database unavailable"), onSelfTest = {}) }
}
