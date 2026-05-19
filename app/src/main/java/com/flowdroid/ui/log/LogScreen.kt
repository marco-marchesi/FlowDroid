package com.flowdroid.ui.log

import android.content.Context
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.ui.formatTimeOfDay
import com.flowdroid.ui.theme.FlowDroidTheme
import com.flowdroid.ui.theme.StatusAmber
import com.flowdroid.ui.theme.StatusRed
import com.flowdroid.ui.theme.StatusUnknown
import com.flowdroid.ui.theme.bodyMonospace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private enum class LogTab(val label: String) { Notifications("Notifications"), AppLog("App log") }

@Composable
fun LogRoute(viewModel: LogViewModel = hiltViewModel()) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val appLog by viewModel.appLog.collectAsStateWithLifecycle()
    val notifFilter by viewModel.notificationFilter.collectAsStateWithLifecycle()
    val logFilter by viewModel.logFilter.collectAsStateWithLifecycle()
    val notifQuery by viewModel.notificationQuery.collectAsStateWithLifecycle()
    val logQuery by viewModel.logQuery.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF launchers — one per tab, parameterised by which exporter to invoke when the user picks
    // a destination. Holding the payload generator in `pendingExporter` lets the export pipeline
    // be a single-method on the VM (we don't need to thread which tab through the contract).
    var pendingExporter by remember { mutableStateOf<(() -> String)?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val payload = pendingExporter
        pendingExporter = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = writeTextToUri(context, uri, payload())
            snackbarHostState.showSnackbar(
                if (ok) "Export saved" else "Export failed — file not written",
            )
        }
    }

    LogScreen(
        notifications = notifications,
        appLog = appLog,
        notificationFilter = notifFilter,
        logFilter = logFilter,
        notificationQuery = notifQuery,
        logQuery = logQuery,
        snackbarHostState = snackbarHostState,
        onNotificationFilter = viewModel::setNotificationFilter,
        onLogFilter = viewModel::setLogFilter,
        onNotificationQuery = viewModel::setNotificationQuery,
        onLogQuery = viewModel::setLogQuery,
        onExportNotifications = {
            pendingExporter = { viewModel.exportNotificationsText() }
            exportLauncher.launch("flowdroid-notifications.txt")
        },
        onExportLog = {
            pendingExporter = { viewModel.exportLogText() }
            exportLauncher.launch("flowdroid-app-log.txt")
        },
    )
}

private suspend fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } ?: false
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            Timber.w(t, "writeTextToUri failed")
            false
        }
    }

@Composable
internal fun LogScreen(
    notifications: List<NotificationEvent>,
    appLog: List<LogEntry>,
    notificationFilter: NotificationFilter,
    logFilter: LogLevelFilter,
    notificationQuery: String = "",
    logQuery: String = "",
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onNotificationFilter: (NotificationFilter) -> Unit,
    onLogFilter: (LogLevelFilter) -> Unit,
    onNotificationQuery: (String) -> Unit = {},
    onLogQuery: (String) -> Unit = {},
    onExportNotifications: () -> Unit = {},
    onExportLog: () -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(LogTab.Notifications) }
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("log_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                LogTab.entries.forEach { tab ->
                    Tab(
                        modifier = Modifier.testTag("log_tab_${tab.name.lowercase()}"),
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }
            when (selectedTab) {
                LogTab.Notifications -> NotificationsTab(
                    events = notifications,
                    filter = notificationFilter,
                    query = notificationQuery,
                    onFilter = onNotificationFilter,
                    onQuery = onNotificationQuery,
                    onExport = onExportNotifications,
                )
                LogTab.AppLog -> AppLogTab(
                    entries = appLog,
                    filter = logFilter,
                    query = logQuery,
                    onFilter = onLogFilter,
                    onQuery = onLogQuery,
                    onExport = onExportLog,
                )
            }
        }
    }
}

@Composable
private fun NotificationsTab(
    events: List<NotificationEvent>,
    filter: NotificationFilter,
    query: String,
    onFilter: (NotificationFilter) -> Unit,
    onQuery: (String) -> Unit,
    onExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().testTag("notifications_tab")) {
        FilterRow {
            NotificationFilter.entries.forEach { opt ->
                FilterChip(
                    modifier = Modifier.testTag("notif_filter_${opt.name}"),
                    selected = filter == opt,
                    onClick = { onFilter(opt) },
                    label = { Text(opt.label) },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                modifier = Modifier.testTag("notif_export"),
                onClick = onExport,
                enabled = events.isNotEmpty(),
            ) { Icon(Icons.Filled.FileDownload, contentDescription = "Export") }
        }
        SearchField(
            query = query,
            onQuery = onQuery,
            placeholder = "Search package / title / text…",
            tag = "notif_search",
        )
        if (events.isEmpty()) {
            EmptyContent(
                if (query.isBlank()) "No notifications yet. Post one and it'll show here."
                else "No matches for \"$query\".",
            )
            return@Column
        }
        // `mutableStateMapOf<Long, Unit>` substitutes for a snapshot-aware set — no `mutableStateSetOf`
        // exists in Compose runtime. `Unit` as value is the standard "set as map keys" trick.
        val expanded = remember { mutableStateMapOf<Long, Unit>() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Use a composite key so preview/fake data with id=0 doesn't collide on sbnKey.
            // In production, id is always > 0 after Room insertion.
            items(events, key = { "${it.id}_${it.sbnKey}" }) { event ->
                val key = "${event.id}_${event.sbnKey}".hashCode().toLong()
                NotificationRow(
                    event = event,
                    isExpanded = expanded.containsKey(key),
                    onClick = {
                        if (expanded.containsKey(key)) expanded.remove(key) else expanded[key] = Unit
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationRow(event: NotificationEvent, isExpanded: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("notif_row_${event.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTimeOfDay(event.postTimeMillis),
                    style = bodyMonospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    event.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                event.title.orEmpty().ifEmpty { "(no title)" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            event.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (isExpanded) Int.MAX_VALUE else 1)
            }
            AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    event.bigText?.takeIf { it.isNotBlank() }?.let {
                        Text("big: $it", style = bodyMonospace)
                    }
                    event.subText?.takeIf { it.isNotBlank() }?.let {
                        Text("sub: $it", style = bodyMonospace)
                    }
                    if (event.actionLabels.isNotEmpty()) {
                        Text("actions: ${event.actionLabels.joinToString()}", style = bodyMonospace)
                    }
                    event.channelId?.let { Text("channel: $it", style = bodyMonospace) }
                    Text("ongoing=${event.isOngoing} clearable=${event.isClearable}", style = bodyMonospace)
                }
            }
        }
    }
}

@Composable
private fun AppLogTab(
    entries: List<LogEntry>,
    filter: LogLevelFilter,
    query: String,
    onFilter: (LogLevelFilter) -> Unit,
    onQuery: (String) -> Unit,
    onExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().testTag("app_log_tab")) {
        FilterRow {
            LogLevelFilter.entries.forEach { opt ->
                FilterChip(
                    modifier = Modifier.testTag("log_filter_${opt.name}"),
                    selected = filter == opt,
                    onClick = { onFilter(opt) },
                    label = { Text(opt.label) },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                modifier = Modifier.testTag("log_export"),
                onClick = onExport,
                enabled = entries.isNotEmpty(),
            ) { Icon(Icons.Filled.FileDownload, contentDescription = "Export") }
        }
        SearchField(
            query = query,
            onQuery = onQuery,
            placeholder = "Search tag / message / fields…",
            tag = "log_search",
        )
        if (entries.isEmpty()) {
            EmptyContent(
                if (query.isBlank()) "Log buffer is empty."
                else "No matches for \"$query\".",
            )
            return@Column
        }
        val expanded = remember { mutableStateMapOf<Long, Unit>() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        ) {
            // Composite key — same reasoning as the notifications list above.
            items(entries, key = { "${it.id}_${it.timestampMillis}_${it.tag}" }) { entry ->
                val key = "${entry.id}_${entry.timestampMillis}_${entry.tag}".hashCode().toLong()
                LogEntryRow(
                    entry = entry,
                    isExpanded = expanded.containsKey(key),
                    onClick = {
                        if (expanded.containsKey(key)) expanded.remove(key) else expanded[key] = Unit
                    },
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry, isExpanded: Boolean, onClick: () -> Unit) {
    val levelColor = entry.level.toColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("log_row_${entry.id}"),
    ) {
        Row {
            Text(
                formatTimeOfDay(entry.timestampMillis),
                style = bodyMonospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                entry.level.name.take(4),
                style = bodyMonospace,
                fontWeight = FontWeight.SemiBold,
                color = levelColor,
            )
            Spacer(Modifier.size(8.dp))
            Text(entry.tag, style = bodyMonospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text(entry.message, style = bodyMonospace, maxLines = if (isExpanded) Int.MAX_VALUE else 2)
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(Modifier.padding(start = 16.dp, top = 4.dp)) {
                entry.fieldsJson?.let { Text("fields: $it", style = bodyMonospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                entry.throwableClass?.let { tc ->
                    Text("${tc}: ${entry.throwableMessage.orEmpty()}", style = bodyMonospace, color = StatusRed)
                }
                entry.stackTraceFirstLines?.let { Text(it, style = bodyMonospace, color = StatusRed) }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    tag: String,
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(tag),
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    modifier = Modifier.testTag("${tag}_clear"),
                    onClick = { onQuery("") },
                ) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }
        },
    )
}

@Composable
private fun FilterRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun EmptyContent(message: String) {
    Box(modifier = Modifier.fillMaxSize().testTag("log_empty"), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogEntry.Level.toColor(): Color = when (this) {
    LogEntry.Level.DEBUG -> StatusUnknown
    LogEntry.Level.INFO -> MaterialTheme.colorScheme.onSurface
    LogEntry.Level.WARN -> StatusAmber
    LogEntry.Level.ERROR -> StatusRed
}

// --- Previews -----------------------------------------------------------------

private fun fakeNotif(id: Long, title: String, text: String, pkg: String = "com.flowdroid.testapp", time: Long = 1_700_000_000_000L) =
    NotificationEvent(
        id = id,
        sbnKey = "k$id",
        packageName = pkg,
        postTimeMillis = time,
        notificationPostTimeMillis = time,
        title = title,
        text = text,
        bigText = null,
        subText = null,
        tickerText = null,
        channelId = "default",
        groupKey = null,
        isOngoing = false,
        isClearable = true,
        isGroupSummary = false,
        importance = 3,
        notificationId = id.toInt(),
        actionLabels = emptyList(),
        rawExtrasJson = null,
    )

private fun fakeLog(id: Long, lvl: LogEntry.Level, msg: String) = LogEntry(
    id = id,
    timestampMillis = 1_700_000_000_000L,
    level = lvl,
    tag = "Watchdog",
    message = msg,
    fieldsJson = null,
    throwableClass = null,
    throwableMessage = null,
    stackTraceFirstLines = null,
)

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LogScreenPreview() {
    FlowDroidTheme {
        LogScreen(
            notifications = listOf(
                fakeNotif(1, "Echo notification", "Sample payload from com.example.app"),
                fakeNotif(2, "Reminder", "Stand-up in 5m", pkg = "com.calendar"),
            ),
            appLog = listOf(
                fakeLog(1, LogEntry.Level.INFO, "Listener bound"),
                fakeLog(2, LogEntry.Level.WARN, "Rebind requested after 4m disconnect"),
                fakeLog(3, LogEntry.Level.ERROR, "Database read failed: corrupted"),
            ),
            notificationFilter = NotificationFilter.All,
            logFilter = LogLevelFilter.AllLevels,
            onNotificationFilter = {},
            onLogFilter = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LogScreenEmptyPreview() {
    FlowDroidTheme {
        LogScreen(
            notifications = emptyList(),
            appLog = emptyList(),
            notificationFilter = NotificationFilter.All,
            logFilter = LogLevelFilter.AllLevels,
            onNotificationFilter = {},
            onLogFilter = {},
        )
    }
}
