package com.flowdroid.ui.flows

import android.content.Context
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.flowdroid.data.flows.ImportError
import com.flowdroid.data.flows.TemplateGallery
import com.flowdroid.ui.theme.FlowDroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun FlowsListRoute(
    navController: NavController,
    viewModel: FlowsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Pending JSON awaiting a user-picked destination Uri. The CreateDocument launcher only
    // returns the Uri — we hold the bytes here so we can write after the user confirms.
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var pendingExportFilename by remember { mutableStateOf("flowdroid-export.json") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = writeJsonToUri(context, uri, json)
            snackbarHostState.showSnackbar(
                if (ok) "Export saved" else "Export failed — file not written",
            )
        }
    }

    var showTemplatesSheet by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = readJsonFromUri(context, uri)
            if (json == null) {
                snackbarHostState.showSnackbar("Import failed — could not read file")
                return@launch
            }
            when (val r = viewModel.importFlowsJson(json)) {
                is ImportResult.Success -> snackbarHostState.showSnackbar(
                    "Imported ${r.count} flow${if (r.count == 1) "" else "s"} (disabled)",
                )
                is ImportResult.Failed -> snackbarHostState.showSnackbar(describeImportError(r.error))
            }
        }
    }

    FlowsListScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onCreate = { navController.navigate("flows/new") },
        onOpen = { id -> navController.navigate("flows/$id") },
        onToggle = viewModel::setEnabled,
        onDelete = viewModel::delete,
        onDuplicate = viewModel::duplicate,
        onViewRuns = { id -> navController.navigate("flows/$id/runs") },
        onExportFlow = { id ->
            scope.launch {
                val json = viewModel.exportFlowJson(id)
                if (json == null) {
                    snackbarHostState.showSnackbar("Export failed — flow not found")
                    return@launch
                }
                pendingExportJson = json
                pendingExportFilename = "flow-$id.json"
                exportLauncher.launch(pendingExportFilename)
            }
        },
        onExportAll = {
            scope.launch {
                val json = viewModel.exportAllFlowsJson()
                pendingExportJson = json
                pendingExportFilename = "flowdroid-export.json"
                exportLauncher.launch(pendingExportFilename)
            }
        },
        onImport = {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        },
        onBrowseTemplates = { showTemplatesSheet = true },
    )

    if (showTemplatesSheet) {
        TemplatesSheet(
            templates = viewModel.availableTemplates,
            onPick = { template ->
                showTemplatesSheet = false
                scope.launch {
                    when (val r = viewModel.importTemplate(template)) {
                        is ImportResult.Success -> snackbarHostState.showSnackbar(
                            "Installed \"${template.title}\" (disabled)",
                        )
                        is ImportResult.Failed -> snackbarHostState.showSnackbar(describeImportError(r.error))
                    }
                }
            },
            onDismiss = { showTemplatesSheet = false },
        )
    }
}

private suspend fun writeJsonToUri(context: Context, uri: Uri, json: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } ?: false
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            Timber.w(t, "writeJsonToUri failed")
            false
        }
    }

private suspend fun readJsonFromUri(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError ||
                t is kotlin.coroutines.cancellation.CancellationException
            ) throw t
            Timber.w(t, "readJsonFromUri failed")
            null
        }
    }

private fun describeImportError(error: ImportError): String = when (error) {
    is ImportError.MalformedJson -> "Import failed — malformed JSON (${error.reason})"
    is ImportError.UnsupportedVersion ->
        "Import failed — unsupported export version ${error.version}"
    ImportError.Empty -> "Import failed — file contains no flows"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlowsListScreen(
    state: FlowsListUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit = {},
    onExportFlow: (String) -> Unit = {},
    onExportAll: () -> Unit = {},
    onImport: () -> Unit = {},
    onBrowseTemplates: () -> Unit = {},
    onViewRuns: (String) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("flows_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Flows") },
                actions = {
                    IconButton(
                        modifier = Modifier.testTag("flows_overflow_button"),
                        onClick = { menuOpen = true },
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            modifier = Modifier.testTag("flows_overflow_export_all"),
                            text = { Text("Export all…") },
                            onClick = {
                                menuOpen = false
                                onExportAll()
                            },
                        )
                        DropdownMenuItem(
                            modifier = Modifier.testTag("flows_overflow_import"),
                            text = { Text("Import…") },
                            onClick = {
                                menuOpen = false
                                onImport()
                            },
                        )
                        DropdownMenuItem(
                            modifier = Modifier.testTag("flows_overflow_templates"),
                            text = { Text("Browse templates…") },
                            onClick = {
                                menuOpen = false
                                onBrowseTemplates()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.testTag("flows_fab_create"),
                onClick = onCreate,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New flow") },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                FlowsListUiState.Loading -> LoadingContent()
                FlowsListUiState.Empty -> EmptyContent(onCreate = onCreate)
                is FlowsListUiState.Error -> ErrorContent(state.message)
                is FlowsListUiState.Ready -> ReadyContent(
                    flows = state.flows,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onDelete = onDelete,
                    onDuplicate = onDuplicate,
                    onExportFlow = onExportFlow,
                    onViewRuns = onViewRuns,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize().testTag("flows_loading"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading flows…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyContent(onCreate: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("flows_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No flows yet. Tap + to create one.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Best for: custom notification reactions and webhook automations.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(
                    modifier = Modifier.testTag("flows_empty_create"),
                    onClick = onCreate,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text(" Create your first flow")
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("flows_error"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  Couldn't load flows",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    flows: List<FlowListItem>,
    onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit = {},
    onExportFlow: (String) -> Unit = {},
    onViewRuns: (String) -> Unit = {},
) {
    var pendingMenu by remember { mutableStateOf<FlowListItem?>(null) }
    var pendingDelete by remember { mutableStateOf<FlowListItem?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("flows_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(flows, key = { it.id }) { row ->
            FlowRow(
                summary = row,
                onOpen = onOpen,
                onToggle = onToggle,
                onLongPress = { pendingMenu = row },
            )
        }
    }

    // Long-press → context menu rendered as a bottom sheet (Material 3). Each row carries a
    // leading icon and a one-line supporting description; the Delete row is tinted error-red so
    // the destructive option is unmistakable. Cancel is the standard sheet dismissal (drag down
    // or scrim tap) — no separate Cancel button needed.
    pendingMenu?.let { target ->
        FlowActionSheet(
            target = target,
            onDismiss = { pendingMenu = null },
            onViewRuns = {
                onViewRuns(target.id)
                pendingMenu = null
            },
            onDuplicate = {
                onDuplicate(target.id)
                pendingMenu = null
            },
            onExport = {
                onExportFlow(target.id)
                pendingMenu = null
            },
            onDelete = {
                pendingMenu = null
                pendingDelete = target
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            modifier = Modifier.testTag("flows_delete_dialog"),
            onDismissRequest = { pendingDelete = null },
            icon = {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete flow?") },
            text = {
                Text(
                    "\"${target.name.ifBlank { "(unnamed flow)" }}\" will be permanently removed. " +
                        "This can't be undone.",
                )
            },
            confirmButton = {
                Button(
                    modifier = Modifier.testTag("flows_delete_confirm"),
                    onClick = {
                        onDelete(target.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlowRow(
    summary: FlowListItem,
    onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("flow_row_${summary.id}")
            .combinedClickable(
                onClick = { onOpen(summary.id) },
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    summary.name.ifBlank { "(unnamed flow)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    summary.triggerSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${summary.actionCount} ${if (summary.actionCount == 1) "action" else "actions"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                modifier = Modifier.testTag("flow_toggle_${summary.id}"),
                checked = summary.enabled,
                onCheckedChange = { onToggle(summary.id, it) },
            )
        }
    }
}

/**
 * Long-press action sheet for one flow. Replaces the old cramped AlertDialog full of side-by-side
 * TextButtons with a proper Material 3 list:
 *  - Each row has a leading family-colored icon, the action label, and a one-line description.
 *  - Delete is rendered in the error palette so the destructive choice is unmistakable.
 *  - Cancel is implicit (sheet drag-to-dismiss / scrim tap) — matches Material 3 norms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowActionSheet(
    target: FlowListItem,
    onDismiss: () -> Unit,
    onViewRuns: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = Modifier.testTag("flows_menu_dialog"),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            // Header card with the flow's name + a hint at what's below.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            ) {
                Text(
                    target.name.ifBlank { "(unnamed flow)" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${target.actionCount} ${if (target.actionCount == 1) "action" else "actions"} · " +
                        if (target.enabled) "enabled" else "disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionSheetRow(
                tag = "flows_menu_runs",
                icon = Icons.Filled.History,
                title = "Runs",
                subtitle = "View per-execution history with timings and errors.",
                onClick = onViewRuns,
            )
            ActionSheetRow(
                tag = "flows_menu_duplicate",
                icon = Icons.Filled.ContentCopy,
                title = "Duplicate",
                subtitle = "Create a disabled copy you can edit independently.",
                onClick = onDuplicate,
            )
            ActionSheetRow(
                tag = "flows_menu_export",
                icon = Icons.Filled.FileDownload,
                title = "Export…",
                subtitle = "Save the flow as a JSON file you can re-import or share.",
                onClick = onExport,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionSheetRow(
                tag = "flows_menu_delete",
                icon = Icons.Filled.DeleteOutline,
                title = "Delete…",
                subtitle = "Permanently remove this flow.",
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ActionSheetRow(
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
        )
        Spacer(Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Bottom-sheet picker for the bundled [TemplateGallery]. Each row carries a description so the
 * user knows what the template tests / how to trigger it. Tapping a row imports the asset via
 * the same `importFlowsJson` pipeline external SAF imports use — the installed flow lands
 * disabled with a fresh UUID, exactly like a hand-imported JSON.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatesSheet(
    templates: List<TemplateGallery.Template>,
    onPick: (TemplateGallery.Template) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = Modifier.testTag("flows_templates_sheet"),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Starter templates",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Each template imports as a disabled flow — open it, customize, then toggle on.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            templates.forEach { template ->
                ListItem(
                    modifier = Modifier.testTag("template_${template.id}"),
                    headlineContent = { Text(template.title) },
                    supportingContent = { Text(template.description) },
                    trailingContent = {
                        TextButton(
                            modifier = Modifier.testTag("template_${template.id}_install"),
                            onClick = { onPick(template) },
                        ) { Text("Install") }
                    },
                )
            }
        }
    }
}

// --- Previews -----------------------------------------------------------------

private val FakeSummaries = listOf(
    FlowListItem(
        id = "1",
        name = "Echo notification",
        enabled = true,
        triggerSummary = "Notification from com.example.app where title matches ^Echo.*",
        actionCount = 1,
        actionSummary = "Click action button matching ^Acknowledge$",
    ),
    FlowListItem(
        id = "2",
        name = "Echo to log",
        enabled = false,
        triggerSummary = "Notification from any app",
        actionCount = 2,
        actionSummary = "Post notification; Launch com.android.settings",
    ),
)

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun FlowsListReadyPreview() {
    FlowDroidTheme {
        FlowsListScreen(
            state = FlowsListUiState.Ready(FakeSummaries),
            onCreate = {},
            onOpen = {},
            onToggle = { _, _ -> },
            onDelete = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun FlowsListEmptyPreview() {
    FlowDroidTheme {
        FlowsListScreen(
            state = FlowsListUiState.Empty,
            onCreate = {},
            onOpen = {},
            onToggle = { _, _ -> },
            onDelete = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun FlowsListLoadingPreview() {
    FlowDroidTheme {
        FlowsListScreen(
            state = FlowsListUiState.Loading,
            onCreate = {},
            onOpen = {},
            onToggle = { _, _ -> },
            onDelete = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun FlowsListErrorPreview() {
    FlowDroidTheme {
        FlowsListScreen(
            state = FlowsListUiState.Error("Database unavailable"),
            onCreate = {},
            onOpen = {},
            onToggle = { _, _ -> },
            onDelete = {},
        )
    }
}
