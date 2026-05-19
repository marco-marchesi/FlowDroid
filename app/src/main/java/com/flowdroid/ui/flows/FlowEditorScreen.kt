package com.flowdroid.ui.flows

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.flowdroid.ui.theme.FlowDroidTheme
import com.flowdroid.ui.theme.bodyMonospace
import kotlinx.coroutines.launch

@Composable
fun FlowEditorRoute(
    flowId: String?,
    navController: NavController,
    viewModel: FlowEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    FlowEditorScreen(
        state = state,
        isNew = flowId == null,
        onBack = { navController.popBackStack() },
        onSave = {
            scope.launch {
                val result = viewModel.save()
                if (result is SaveResult.Success) navController.popBackStack()
            }
        },
        onSetName = viewModel::setName,
        onSetEnabled = viewModel::setEnabled,
        onAddTrigger = { viewModel.addTrigger(it) },
        onUpdateTrigger = viewModel::updateTriggerAt,
        onRemoveTrigger = viewModel::removeTriggerAt,
        onToggleTriggerCollapsed = viewModel::toggleTriggerCollapsed,
        onAddAction = viewModel::addAction,
        onUpdateAction = viewModel::updateActionAt,
        onRemoveAction = viewModel::removeActionAt,
        onDuplicateAction = viewModel::duplicateActionAt,
        onMoveAction = viewModel::moveAction,
        onToggleActionCollapsed = viewModel::toggleActionCollapsed,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlowEditorScreen(
    state: FlowEditorUiState,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSetName: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onAddTrigger: (Trigger) -> Unit,
    onUpdateTrigger: (Int, Trigger) -> Unit,
    onRemoveTrigger: (Int) -> Unit,
    onToggleTriggerCollapsed: (Int) -> Unit = {},
    onAddAction: (Action) -> Unit,
    onUpdateAction: (Int, Action) -> Unit,
    onRemoveAction: (Int) -> Unit,
    onDuplicateAction: (Int) -> Unit = {},
    onMoveAction: (Int, Int) -> Unit = { _, _ -> },
    onToggleActionCollapsed: (Int) -> Unit = {},
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showAddTriggerSheet by remember { mutableStateOf(false) }

    val tryBack: () -> Unit = {
        if (state.isDirty) confirmDiscard = true else onBack()
    }
    BackHandler { tryBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("flow_editor_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) "New flow" else "Edit flow",
                        modifier = Modifier.testTag("flow_editor_title"),
                    )
                },
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.testTag("flow_editor_back"),
                        onClick = tryBack,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    FilledTonalButton(
                        modifier = Modifier.testTag("flow_editor_save"),
                        onClick = onSave,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  Save")
                    }
                },
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        // Reorderable wraps the LazyColumn — we trigger moves only for the action rows by
        // matching their stable keys. Other items (header, triggers, etc.) are ignored.
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
            val toKey = to.key as? String ?: return@rememberReorderableLazyListState
            if (!fromKey.startsWith("action_") || !toKey.startsWith("action_")) return@rememberReorderableLazyListState
            val fromIdx = fromKey.removePrefix("action_").toIntOrNull() ?: return@rememberReorderableLazyListState
            val toIdx = toKey.removePrefix("action_").toIntOrNull() ?: return@rememberReorderableLazyListState
            onMoveAction(fromIdx, toIdx)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("flow_editor_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { NameCard(state, onSetName, onSetEnabled) }
            item {
                TriggersHeader()
            }
            itemsIndexed(
                items = state.draft.triggers,
                key = { i, _ -> "trigger_$i" },
            ) { index, trigger ->
                TriggerCard(
                    index = index,
                    trigger = trigger,
                    canRemove = state.draft.triggers.size > 1,
                    collapsed = index in state.collapsedTriggers,
                    warning = state.errors.triggerWarnings[index],
                    onToggleCollapsed = { onToggleTriggerCollapsed(index) },
                    onUpdate = { onUpdateTrigger(index, it) },
                    onRemove = { onRemoveTrigger(index) },
                )
            }
            item { AddTriggerButton(onAdd = { showAddTriggerSheet = true }) }
            item { ActionsHeader(onAdd = { showAddSheet = true }) }
            itemsIndexed(items = state.draft.actions, key = { i, _ -> "action_$i" }) { index, action ->
                ReorderableItem(reorderState, key = "action_$index") { isDragging ->
                    ActionCard(
                        index = index,
                        action = action,
                        collapsed = index in state.collapsedActions,
                        isDragging = isDragging,
                        dragHandleModifier = Modifier.draggableHandle(),
                        error = state.errors.actionErrors[index],
                        onToggleCollapsed = { onToggleActionCollapsed(index) },
                        onUpdate = { onUpdateAction(index, it) },
                        onRemove = { onRemoveAction(index) },
                        onDuplicate = { onDuplicateAction(index) },
                    )
                }
            }
            state.saveError?.let {
                item {
                    Text(
                        "Couldn't save: $it",
                        modifier = Modifier.testTag("flow_editor_save_error"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddActionSheet(
            onPick = {
                onAddAction(it)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }

    if (showAddTriggerSheet) {
        AddTriggerSheet(
            onPick = {
                onAddTrigger(it)
                showAddTriggerSheet = false
            },
            onDismiss = { showAddTriggerSheet = false },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            modifier = Modifier.testTag("flow_editor_discard_dialog"),
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Leave without saving?") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("flow_editor_discard_confirm"),
                    onClick = {
                        confirmDiscard = false
                        onBack()
                    },
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun NameCard(
    state: FlowEditorUiState,
    onSetName: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    SectionCard(title = "Name") {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("flow_editor_name_field"),
            value = state.draft.name,
            onValueChange = onSetName,
            label = { Text("Flow name") },
            singleLine = true,
            isError = state.errors.nameError != null,
            supportingText = state.errors.nameError?.let { { Text(it) } },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enabled", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                modifier = Modifier.testTag("flow_editor_enabled"),
                checked = state.draft.enabled,
                onCheckedChange = onSetEnabled,
            )
        }
    }
}

@Composable
private fun TriggersHeader() {
    Text(
        "Triggers (any fires the flow)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.testTag("flow_editor_triggers_header"),
    )
}

@Composable
private fun AddTriggerButton(onAdd: () -> Unit) {
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            modifier = Modifier.testTag("flow_editor_add_trigger"),
            onClick = onAdd,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" Add trigger")
        }
    }
}

@Composable
private fun TriggerCard(
    index: Int,
    trigger: Trigger,
    canRemove: Boolean,
    collapsed: Boolean,
    warning: String?,
    onToggleCollapsed: () -> Unit,
    onUpdate: (Trigger) -> Unit,
    onRemove: () -> Unit,
) {
    val visuals = visualsFor(trigger)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("flow_editor_trigger_$index"),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(visuals.color),
            )
        Column(Modifier.padding(start = 12.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FamilyNode(visuals.color, visuals.glyph)
                Spacer(Modifier.width(8.dp))
                IndexPill(text = "T", color = visuals.color)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val customLabel = trigger.label?.takeIf { it.isNotBlank() }
                    val typeLabel = triggerTypeLabel(trigger)
                    val headline = customLabel
                        ?: "Trigger ${index + 1} — $typeLabel"
                    Text(
                        headline,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (collapsed) {
                        Text(
                            summarizeTrigger(trigger),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (customLabel != null) {
                        Text(
                            "Trigger ${index + 1} · $typeLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    modifier = Modifier.testTag("flow_editor_trigger_${index}_collapse"),
                    onClick = onToggleCollapsed,
                ) {
                    Icon(
                        if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (collapsed) "Expand trigger" else "Collapse trigger",
                    )
                }
                IconButton(
                    modifier = Modifier.testTag("flow_editor_trigger_${index}_remove"),
                    onClick = { if (canRemove) onRemove() },
                    enabled = canRemove,
                ) { Icon(Icons.Filled.Close, contentDescription = "Remove trigger") }
            }
            if (!collapsed) {
                Spacer(Modifier.height(8.dp))
                // Type swap dropdown — lets the user pivot trigger kind without removing/re-adding.
                TriggerTypeDropdown(
                    index = index,
                    current = trigger,
                    onPick = { entry -> onUpdate(entry.factory()) },
                )
                Spacer(Modifier.height(8.dp))
                LabelField(
                    value = trigger.label.orEmpty(),
                    tag = "flow_editor_trigger_${index}_label",
                    onChange = { value ->
                        onUpdate(trigger.withLabel(value.ifBlank { null }))
                    },
                )
                when (trigger) {
                    is Trigger.NotificationPosted -> NotificationTriggerFields(
                        index = index,
                        trigger = trigger,
                        warning = warning,
                        onUpdate = onUpdate,
                    )
                    is Trigger.TimeOfDay -> TimeOfDayTriggerFields(
                        index = index,
                        trigger = trigger,
                        onUpdate = onUpdate,
                    )
                    is Trigger.Interval -> IntervalTriggerFields(
                        index = index,
                        trigger = trigger,
                        onUpdate = onUpdate,
                    )
                    is Trigger.Webhook -> WebhookTriggerFields(
                        index = index,
                        trigger = trigger,
                        onUpdate = onUpdate,
                    )
                    else -> Text("Unknown trigger type (${trigger::class.simpleName})")
                }
            }
        }
        }
    }
}

/**
 * Optional user-supplied label rendered as the card header when non-blank. Shared between
 * trigger and action cards.
 */
@Composable
private fun LabelField(value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        value = value,
        onValueChange = onChange,
        label = { Text("Label (optional)") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NotificationTriggerFields(
    index: Int,
    trigger: Trigger.NotificationPosted,
    warning: String?,
    onUpdate: (Trigger) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    PackageField(
        value = trigger.packageName.orEmpty(),
        label = "Package name (exact, e.g. com.example.app)",
        tag = "flow_editor_trigger_${index}_package",
        onChange = { onUpdate(trigger.copy(packageName = it.ifBlank { null })) },
        onPick = { showPicker = true },
    )
    RegexField(
        value = trigger.titleRegex.orEmpty(),
        label = "Title regex",
        tag = "flow_editor_trigger_${index}_title",
        onChange = { onUpdate(trigger.copy(titleRegex = it.ifBlank { null })) },
    )
    RegexField(
        value = trigger.textRegex.orEmpty(),
        label = "Text regex",
        tag = "flow_editor_trigger_${index}_text",
        onChange = { onUpdate(trigger.copy(textRegex = it.ifBlank { null })) },
    )
    RegexField(
        value = trigger.actionLabelRegex.orEmpty(),
        label = "Action label regex (e.g. ^Acknowledge$)",
        tag = "flow_editor_trigger_${index}_action_label",
        onChange = { onUpdate(trigger.copy(actionLabelRegex = it.ifBlank { null })) },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Exclude ongoing", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            modifier = Modifier.testTag("flow_editor_trigger_${index}_exclude_ongoing"),
            checked = trigger.excludeOngoing,
            onCheckedChange = { onUpdate(trigger.copy(excludeOngoing = it)) },
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("flow_editor_trigger_${index}_debounce"),
        value = trigger.debounceMillis.toString(),
        onValueChange = { raw ->
            val parsed = raw.filter { it.isDigit() }.toLongOrNull() ?: 0L
            onUpdate(trigger.copy(debounceMillis = parsed))
        },
        label = { Text("Debounce (ms)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
    warning?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            modifier = Modifier.testTag("flow_editor_trigger_${index}_warning"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    if (showPicker) {
        PackagePickerSheet(
            onPick = {
                onUpdate(trigger.copy(packageName = it.ifBlank { null }))
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun PackageField(
    value: String,
    label: String,
    tag: String,
    onChange: (String) -> Unit,
    onPick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag(tag),
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            textStyle = bodyMonospace,
        )
        OutlinedButton(
            modifier = Modifier.padding(start = 8.dp).testTag("${tag}_pick"),
            onClick = onPick,
        ) { Text("Pick…") }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RegexField(
    value: String,
    label: String,
    tag: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ActionsHeader(onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            modifier = Modifier.testTag("flow_editor_add_action"),
            onClick = onAdd,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" Add action")
        }
    }
}

@Composable
private fun ActionCard(
    index: Int,
    action: Action,
    collapsed: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    error: String?,
    onToggleCollapsed: () -> Unit,
    onUpdate: (Action) -> Unit,
    onRemove: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val elevation = if (isDragging) 8.dp else 1.dp
    val visuals = visualsFor(action)
    // Rail-style card from the mockup: 4dp family-coloured stripe down the left edge of the
    // card, with the rest of the card neutral. The Box(stripe) lives inside the Card so the
    // stripe inherits the same rounded clipping — no need to play with custom shapes.
    Card(
        modifier = Modifier.fillMaxWidth().testTag("flow_editor_action_$index"),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(visuals.color),
            )
            Column(Modifier.padding(start = 12.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Family-coloured node + index pill — replaces the previous drag/collapse icons
                // at the very start of the header. Drag handle still present, but pushed right
                // of the pill so the rail visual reads first.
                FamilyNode(visuals.color, visuals.glyph)
                Spacer(Modifier.width(8.dp))
                IndexPill(text = "${index + 1}", color = visuals.color)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val customLabel = action.label?.takeIf { it.isNotBlank() }
                    val typeLabel = actionTypeLabel(action)
                    Text(
                        customLabel ?: typeLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (collapsed) {
                        Text(
                            summarizeAction(action),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (customLabel != null) {
                        Text(
                            typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    modifier = dragHandleModifier.testTag("flow_editor_action_${index}_drag"),
                    onClick = {},
                ) { Icon(Icons.Filled.DragHandle, contentDescription = "Drag to reorder") }
                IconButton(
                    modifier = Modifier.testTag("flow_editor_action_${index}_collapse"),
                    onClick = onToggleCollapsed,
                ) {
                    Icon(
                        if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (collapsed) "Expand action" else "Collapse action",
                    )
                }
                IconButton(
                    modifier = Modifier.testTag("flow_editor_action_${index}_duplicate"),
                    onClick = onDuplicate,
                ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate action") }
                IconButton(
                    modifier = Modifier.testTag("flow_editor_action_${index}_remove"),
                    onClick = onRemove,
                ) { Icon(Icons.Filled.Close, contentDescription = "Remove action") }
            }
            if (!collapsed) {
                Spacer(Modifier.height(8.dp))
                LabelField(
                    value = action.label.orEmpty(),
                    tag = "flow_editor_action_${index}_label",
                    onChange = { onUpdate(action.withLabel(it.ifBlank { null })) },
                )
                when (action) {
                    is Action.ClickNotificationAction -> ClickActionFields(action, error, onUpdate)
                    is Action.LaunchApp -> LaunchAppFields(action, error, onUpdate)
                    is Action.PostNotification -> PostNotificationFields(action, error, onUpdate)
                    is Action.Delay -> DelayFields(action, error, onUpdate)
                    is Action.KillApp -> KillAppFields(action, error, onUpdate)
                    is Action.UiClick -> UiClickFields(action, error, onUpdate)
                    is Action.UiSwipe -> UiSwipeFields(action, error, onUpdate)
                    is Action.UiTypeText -> UiTypeTextFields(action, error, onUpdate)
                    is Action.UiPressKey -> UiPressKeyFields(action, onUpdate)
                    is Action.Http -> HttpActionFields(action, error, onUpdate)
                    is Action.SetVariable -> SetVariableFields(action, error, onUpdate)
                    is Action.ReadFile -> ReadFileFields(action, error, onUpdate)
                    is Action.WriteFile -> WriteFileFields(action, error, onUpdate)
                    is Action.Base64 -> Base64Fields(action, error, onUpdate)
                    is Action.Hash -> HashFields(action, error, onUpdate)
                    is Action.If -> IfFields(action, error, onUpdate)
                    is Action.Loop -> LoopFields(action, error, onUpdate)
                    is Action.TryCatch -> TryCatchFields(action, onUpdate)
                    is Action.UnlockScreen -> UnlockScreenFields(action, onUpdate)
                    else -> Text("Unknown action")
                }
            }
            }
        }
    }
}

@Composable
private fun ClickActionFields(
    action: Action.ClickNotificationAction,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_click_label"),
        value = action.labelRegex,
        onValueChange = { onUpdate(action.copy(labelRegex = it)) },
        label = { Text("Button label regex (required)") },
        singleLine = true,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dismiss after click", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            modifier = Modifier.testTag("action_click_dismiss"),
            checked = action.dismissAfter,
            onCheckedChange = { onUpdate(action.copy(dismissAfter = it)) },
        )
    }
    ContinueOnErrorSwitch(action.continueOnError, "action_click_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun LaunchAppFields(
    action: Action.LaunchApp,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    PackageField(
        value = action.packageName,
        label = "Package name (required)",
        tag = "action_launch_package",
        onChange = { onUpdate(action.copy(packageName = it)) },
        onPick = { showPicker = true },
    )
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_launch_activity"),
        value = action.activityClass.orEmpty(),
        onValueChange = { onUpdate(action.copy(activityClass = it.ifBlank { null })) },
        label = { Text("Activity class (optional)") },
        singleLine = true,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_launch_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
    if (showPicker) {
        PackagePickerSheet(
            onPick = {
                onUpdate(action.copy(packageName = it))
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun PostNotificationFields(
    action: Action.PostNotification,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_post_title"),
        value = action.title,
        onValueChange = { onUpdate(action.copy(title = it)) },
        label = { Text("Title") },
        singleLine = true,
        isError = error != null,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_post_text"),
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text("Text") },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_post_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun DelayFields(
    action: Action.Delay,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    val (millisText, onMillisChange) = rememberLongDigitState(action.millis) {
        onUpdate(action.copy(millis = it))
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_delay_millis"),
        value = millisText,
        onValueChange = onMillisChange,
        label = { Text("Duration (ms)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_delay_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun KillAppFields(
    action: Action.KillApp,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    PackageField(
        value = action.packageName,
        label = "Package name (required)",
        tag = "action_kill_package",
        onChange = { onUpdate(action.copy(packageName = it)) },
        onPick = { showPicker = true },
    )
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
    }
    Text(
        "Note: without root, only background apps can be killed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_kill_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
    if (showPicker) {
        PackagePickerSheet(
            onPick = {
                onUpdate(action.copy(packageName = it))
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun UiClickFields(
    action: Action.UiClick,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    EnumDropdown(
        tag = "action_uiclick_target_mode",
        label = "Target mode",
        values = UiTargetMode.values().toList(),
        selected = action.targetMode,
        toLabel = { humanTargetMode(it) },
        onSelect = { onUpdate(action.copy(targetMode = it)) },
    )
    Spacer(Modifier.height(8.dp))
    when (action.targetMode) {
        UiTargetMode.COORDINATES -> {
            FloatField("X (px)", action.x, "action_uiclick_x") { onUpdate(action.copy(x = it)) }
            FloatField("Y (px)", action.y, "action_uiclick_y") { onUpdate(action.copy(y = it)) }
        }
        UiTargetMode.BY_TEXT -> {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("action_uiclick_text"),
                value = action.text.orEmpty(),
                onValueChange = { onUpdate(action.copy(text = it.ifBlank { null })) },
                label = { Text("Text") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            EnumDropdown(
                tag = "action_uiclick_text_match",
                label = "Text match",
                values = TextMatch.values().toList(),
                selected = action.textMatch,
                toLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                onSelect = { onUpdate(action.copy(textMatch = it)) },
            )
            Spacer(Modifier.height(8.dp))
        }
        UiTargetMode.BY_CONTENT_DESCRIPTION -> {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("action_uiclick_content_desc"),
                value = action.contentDescription.orEmpty(),
                onValueChange = { onUpdate(action.copy(contentDescription = it.ifBlank { null })) },
                label = { Text("Content description") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
        }
        UiTargetMode.BY_VIEW_ID -> {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("action_uiclick_view_id"),
                value = action.viewId.orEmpty(),
                onValueChange = { onUpdate(action.copy(viewId = it.ifBlank { null })) },
                label = { Text("View id (e.g. com.app:id/btn)") },
                singleLine = true,
                textStyle = bodyMonospace,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    EnumDropdown(
        tag = "action_uiclick_click_mode",
        label = "Click mode",
        values = ClickMode.values().toList(),
        selected = action.clickMode,
        toLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
        onSelect = { onUpdate(action.copy(clickMode = it)) },
    )
    Spacer(Modifier.height(8.dp))
    if (action.clickMode == ClickMode.LONG) {
        LongField(
            label = "Long-press duration (ms)",
            value = action.longPressDurationMs,
            tag = "action_uiclick_long_duration",
            onChange = { onUpdate(action.copy(longPressDurationMs = it)) },
        )
    }
    if (action.targetMode != UiTargetMode.COORDINATES) {
        var showPicker by remember { mutableStateOf(false) }
        PackageField(
            value = action.inPackage.orEmpty(),
            label = "In package (optional)",
            tag = "action_uiclick_in_package",
            onChange = { onUpdate(action.copy(inPackage = it.ifBlank { null })) },
            onPick = { showPicker = true },
        )
        if (showPicker) {
            PackagePickerSheet(
                onPick = {
                    onUpdate(action.copy(inPackage = it.ifBlank { null }))
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
    LongField(
        label = "Timeout (ms)",
        value = action.timeoutMs,
        tag = "action_uiclick_timeout",
        onChange = { onUpdate(action.copy(timeoutMs = it)) },
    )
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
    }
    ContinueOnErrorSwitch(action.continueOnError, "action_uiclick_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun UiSwipeFields(
    action: Action.UiSwipe,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    val byDirection = action.direction != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mode", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AssistChip(
            modifier = Modifier.testTag("action_uiswipe_mode_direction"),
            onClick = { onUpdate(action.copy(direction = action.direction ?: SwipeDirection.UP)) },
            label = { Text(if (byDirection) "Direction ✓" else "Direction") },
        )
        Spacer(Modifier.height(0.dp))
        AssistChip(
            modifier = Modifier.padding(start = 8.dp).testTag("action_uiswipe_mode_coords"),
            onClick = { onUpdate(action.copy(direction = null)) },
            label = { Text(if (!byDirection) "Coordinates ✓" else "Coordinates") },
        )
    }
    Spacer(Modifier.height(8.dp))
    if (byDirection) {
        EnumDropdown(
            tag = "action_uiswipe_direction",
            label = "Direction",
            values = SwipeDirection.values().toList(),
            selected = action.direction ?: SwipeDirection.UP,
            toLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = { onUpdate(action.copy(direction = it)) },
        )
        Spacer(Modifier.height(8.dp))
    } else {
        FloatField("From X", action.fromX, "action_uiswipe_from_x") { onUpdate(action.copy(fromX = it)) }
        FloatField("From Y", action.fromY, "action_uiswipe_from_y") { onUpdate(action.copy(fromY = it)) }
        FloatField("To X", action.toX, "action_uiswipe_to_x") { onUpdate(action.copy(toX = it)) }
        FloatField("To Y", action.toY, "action_uiswipe_to_y") { onUpdate(action.copy(toY = it)) }
    }
    LongField(
        label = "Duration (ms)",
        value = action.durationMs,
        tag = "action_uiswipe_duration",
        onChange = { onUpdate(action.copy(durationMs = it)) },
    )
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    ContinueOnErrorSwitch(action.continueOnError, "action_uiswipe_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun UiTypeTextFields(
    action: Action.UiTypeText,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_uitype_text"),
        value = action.text,
        onValueChange = { onUpdate(action.copy(text = it)) },
        label = { Text("Text to type") },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Press Enter after", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            modifier = Modifier.testTag("action_uitype_enter"),
            checked = action.pressEnterAfter,
            onCheckedChange = { onUpdate(action.copy(pressEnterAfter = it)) },
        )
    }
    ContinueOnErrorSwitch(action.continueOnError, "action_uitype_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun UiPressKeyFields(
    action: Action.UiPressKey,
    onUpdate: (Action) -> Unit,
) {
    EnumDropdown(
        tag = "action_uikey_key",
        label = "Key",
        values = UiKey.values().toList(),
        selected = action.key,
        toLabel = { humanKey(it) },
        onSelect = { onUpdate(action.copy(key = it)) },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_uikey_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun HttpActionFields(
    action: Action.Http,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    EnumDropdown(
        tag = "action_http_method",
        label = "Method",
        values = com.flowdroid.common.flow.HttpMethod.values().toList(),
        selected = action.method,
        toLabel = { it.name },
        onSelect = { onUpdate(action.copy(method = it)) },
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_http_url"),
        value = action.url,
        onValueChange = { onUpdate(action.copy(url = it)) },
        label = { Text("URL (required, magic-text supported)") },
        singleLine = true,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))

    // ── Headers ────────────────────────────────────────────────────────
    Text("Headers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    action.headers.forEachIndexed { idx, header ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                modifier = Modifier.weight(0.4f).testTag("action_http_header_name_$idx"),
                value = header.name,
                onValueChange = { newName ->
                    val updated = action.headers.toMutableList()
                    updated[idx] = header.copy(name = newName)
                    onUpdate(action.copy(headers = updated))
                },
                label = { Text("Name") },
                singleLine = true,
                textStyle = bodyMonospace,
            )
            Spacer(Modifier.height(0.dp))
            OutlinedTextField(
                modifier = Modifier.weight(0.6f).testTag("action_http_header_value_$idx"),
                value = header.value,
                onValueChange = { newValue ->
                    val updated = action.headers.toMutableList()
                    updated[idx] = header.copy(value = newValue)
                    onUpdate(action.copy(headers = updated))
                },
                label = { Text("Value") },
                singleLine = true,
                textStyle = bodyMonospace,
            )
            IconButton(
                modifier = Modifier.testTag("action_http_header_remove_$idx"),
                onClick = {
                    val updated = action.headers.toMutableList()
                    updated.removeAt(idx)
                    onUpdate(action.copy(headers = updated))
                },
            ) { Icon(Icons.Filled.Close, contentDescription = "Remove header") }
        }
        Spacer(Modifier.height(4.dp))
    }
    OutlinedButton(
        modifier = Modifier.testTag("action_http_add_header"),
        onClick = {
            onUpdate(
                action.copy(
                    headers = action.headers + com.flowdroid.common.flow.HttpHeader(name = "", value = ""),
                ),
            )
        },
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(" Add header")
    }
    Spacer(Modifier.height(8.dp))

    // ── Body ───────────────────────────────────────────────────────────
    EnumDropdown(
        tag = "action_http_body_type",
        label = "Body type",
        values = com.flowdroid.common.flow.HttpBodyType.values().toList(),
        selected = action.bodyContentType,
        toLabel = { it.name },
        onSelect = { onUpdate(action.copy(bodyContentType = it)) },
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_http_body"),
        value = action.body.orEmpty(),
        onValueChange = { onUpdate(action.copy(body = it.ifBlank { null })) },
        label = { Text("Body (magic-text supported)") },
        minLines = 3,
        maxLines = 8,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))

    // ── Timeouts + expected status ─────────────────────────────────────
    LongField(
        label = "Timeout (ms)",
        value = action.timeoutMs,
        tag = "action_http_timeout",
        onChange = { onUpdate(action.copy(timeoutMs = it.coerceAtLeast(1000L))) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (minText, onMinChange) = rememberIntDigitState(action.expectStatusMin) {
            onUpdate(action.copy(expectStatusMin = it))
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("action_http_status_min"),
            value = minText,
            onValueChange = onMinChange,
            label = { Text("Expect status ≥") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(0.dp))
        val (maxText, onMaxChange) = rememberIntDigitState(action.expectStatusMax) {
            onUpdate(action.copy(expectStatusMax = it))
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("action_http_status_max"),
            value = maxText,
            onValueChange = onMaxChange,
            label = { Text("Expect status ≤") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Spacer(Modifier.height(8.dp))

    // ── Response variable storage ──────────────────────────────────────
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_http_store_status"),
        value = action.storeStatusInVar.orEmpty(),
        onValueChange = { onUpdate(action.copy(storeStatusInVar = it.ifBlank { null })) },
        label = { Text("Store status in variable (optional)") },
        singleLine = true,
        supportingText = { Text("Use as {var.<name>} in the next action.") },
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_http_store_body"),
        value = action.storeBodyInVar.orEmpty(),
        onValueChange = { onUpdate(action.copy(storeBodyInVar = it.ifBlank { null })) },
        label = { Text("Store body in variable (optional)") },
        singleLine = true,
        supportingText = {
            Text("Combine with |jsonpath:$.path or |regex:pattern in next placeholder.")
        },
    )
    Spacer(Modifier.height(8.dp))

    Text(
        "Auth: paste a Cookie or Authorization header value (DevTools → Network on the desktop site → " +
            "request to /api/… → copy header value).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    // ── Retry on failure ───────────────────────────────────────────────
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(
        "Retry on failure",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    val (retriesText, onRetriesChange) = rememberIntDigitState(action.retries) {
        onUpdate(action.copy(retries = it.coerceIn(0, Action.Http.MAX_RETRIES)))
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_http_retries"),
        value = retriesText,
        onValueChange = onRetriesChange,
        label = { Text("Extra attempts after the first (0–${Action.Http.MAX_RETRIES})") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("0 = no retry. Keep total wall-time inside the 30s per-action cap.") },
    )
    Spacer(Modifier.height(4.dp))
    val (backoffText, onBackoffChange) = rememberLongDigitState(action.retryBackoffMs) {
        onUpdate(action.copy(retryBackoffMs = it.coerceAtLeast(0L)))
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_http_retry_backoff"),
        value = backoffText,
        onValueChange = onBackoffChange,
        label = { Text("Backoff base (ms) — doubles each attempt") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (retryMinText, onRetryMinChange) = rememberIntDigitState(action.retryOnStatusMin) {
            onUpdate(action.copy(retryOnStatusMin = it))
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("action_http_retry_status_min"),
            value = retryMinText,
            onValueChange = onRetryMinChange,
            label = { Text("Retry status ≥") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(0.dp))
        val (retryMaxText, onRetryMaxChange) = rememberIntDigitState(action.retryOnStatusMax) {
            onUpdate(action.copy(retryOnStatusMax = it))
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("action_http_retry_status_max"),
            value = retryMaxText,
            onValueChange = onRetryMaxChange,
            label = { Text("Retry status ≤") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Spacer(Modifier.height(8.dp))

    ContinueOnErrorSwitch(action.continueOnError, "action_http_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun SetVariableFields(
    action: Action.SetVariable,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_setvar_name"),
        value = action.name,
        onValueChange = { onUpdate(action.copy(name = it)) },
        label = { Text("Variable name (required, literal — no magic text)") },
        singleLine = true,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_setvar_value"),
        value = action.value,
        onValueChange = { onUpdate(action.copy(value = it)) },
        label = { Text("Value (magic-text supported)") },
        minLines = 2,
        maxLines = 5,
        textStyle = bodyMonospace,
        supportingText = {
            Text("e.g. {notification.title} or {var.body|jsonpath:\$.matches[0].id}")
        },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_setvar_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun ReadFileFields(
    action: Action.ReadFile,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_readfile_path"),
        value = action.path,
        onValueChange = { onUpdate(action.copy(path = it)) },
        label = { Text("Relative path inside app-private automation/ (magic-text)") },
        singleLine = true,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } }
            ?: { Text("e.g. cache/token.txt — \"..\" and absolute paths rejected") },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_readfile_intovar"),
        value = action.intoVar,
        onValueChange = { onUpdate(action.copy(intoVar = it)) },
        label = { Text("Store contents in variable (literal — no magic text)") },
        singleLine = true,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_readfile_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun WriteFileFields(
    action: Action.WriteFile,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_writefile_path"),
        value = action.path,
        onValueChange = { onUpdate(action.copy(path = it)) },
        label = { Text("Relative path inside app-private automation/ (magic-text)") },
        singleLine = true,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } }
            ?: { Text("Parent directories are created automatically.") },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_writefile_content"),
        value = action.content,
        onValueChange = { onUpdate(action.copy(content = it)) },
        label = { Text("Content (magic-text supported)") },
        minLines = 2,
        maxLines = 6,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
    EnumDropdown(
        tag = "action_writefile_mode",
        label = "Mode",
        values = com.flowdroid.common.flow.FileWriteMode.entries.toList(),
        selected = action.mode,
        toLabel = { m ->
            when (m) {
                com.flowdroid.common.flow.FileWriteMode.OVERWRITE -> "Overwrite"
                com.flowdroid.common.flow.FileWriteMode.APPEND -> "Append"
            }
        },
        onSelect = { onUpdate(action.copy(mode = it)) },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_writefile_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun Base64Fields(
    action: Action.Base64,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_base64_input"),
        value = action.input,
        onValueChange = { onUpdate(action.copy(input = it)) },
        label = { Text("Input (magic-text supported)") },
        minLines = 2,
        maxLines = 5,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))
    EnumDropdown(
        tag = "action_base64_mode",
        label = "Mode",
        values = com.flowdroid.common.flow.Base64Mode.entries.toList(),
        selected = action.mode,
        toLabel = { m ->
            when (m) {
                com.flowdroid.common.flow.Base64Mode.ENCODE -> "Encode"
                com.flowdroid.common.flow.Base64Mode.DECODE -> "Decode"
            }
        },
        onSelect = { onUpdate(action.copy(mode = it)) },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_base64_intovar"),
        value = action.intoVar,
        onValueChange = { onUpdate(action.copy(intoVar = it)) },
        label = { Text("Store result in variable (literal — no magic text)") },
        singleLine = true,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_base64_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun HashFields(
    action: Action.Hash,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_hash_input"),
        value = action.input,
        onValueChange = { onUpdate(action.copy(input = it)) },
        label = { Text("Input (magic-text supported)") },
        minLines = 2,
        maxLines = 5,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
    Spacer(Modifier.height(8.dp))
    EnumDropdown(
        tag = "action_hash_algorithm",
        label = "Algorithm",
        values = com.flowdroid.common.flow.HashAlgorithm.entries.toList(),
        selected = action.algorithm,
        toLabel = { a ->
            when (a) {
                com.flowdroid.common.flow.HashAlgorithm.MD5 -> "MD5"
                com.flowdroid.common.flow.HashAlgorithm.SHA1 -> "SHA-1"
                com.flowdroid.common.flow.HashAlgorithm.SHA256 -> "SHA-256"
                com.flowdroid.common.flow.HashAlgorithm.SHA512 -> "SHA-512"
            }
        },
        onSelect = { onUpdate(action.copy(algorithm = it)) },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_hash_intovar"),
        value = action.intoVar,
        onValueChange = { onUpdate(action.copy(intoVar = it)) },
        label = { Text("Store hex digest in variable (literal — no magic text)") },
        singleLine = true,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_hash_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun IfFields(
    action: Action.If,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    val unaryOp = action.condition.op == com.flowdroid.common.flow.CompareOp.IS_BLANK ||
        action.condition.op == com.flowdroid.common.flow.CompareOp.IS_NOT_BLANK

    // ── Condition row ────────────────────────────────────────────────────────
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_if_left"),
        value = action.condition.left,
        onValueChange = {
            onUpdate(action.copy(condition = action.condition.copy(left = it)))
        },
        label = { Text("Left (magic-text supported)") },
        singleLine = true,
        textStyle = bodyMonospace,
        isError = error != null,
        supportingText = error?.let { { Text(it) } }
            ?: { Text("e.g. {var.status} or {notification.title}") },
    )
    Spacer(Modifier.height(8.dp))
    EnumDropdown(
        tag = "action_if_op",
        label = "Operator",
        values = com.flowdroid.common.flow.CompareOp.entries.toList(),
        selected = action.condition.op,
        toLabel = { humanCompareOp(it) },
        onSelect = {
            onUpdate(action.copy(condition = action.condition.copy(op = it)))
        },
    )
    Spacer(Modifier.height(8.dp))
    if (!unaryOp) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("action_if_right"),
            value = action.condition.right,
            onValueChange = {
                onUpdate(action.copy(condition = action.condition.copy(right = it)))
            },
            label = { Text("Right (magic-text supported)") },
            singleLine = true,
            textStyle = bodyMonospace,
        )
        Spacer(Modifier.height(8.dp))
    }
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))

    // ── Then branch ──────────────────────────────────────────────────────────
    NestedBranch(
        branchLabel = "Then",
        branchTag = "then",
        actions = action.thenActions,
        onUpdate = { newList -> onUpdate(action.copy(thenActions = newList)) },
    )
    Spacer(Modifier.height(12.dp))

    // ── Else branch ──────────────────────────────────────────────────────────
    NestedBranch(
        branchLabel = "Else (optional)",
        branchTag = "else",
        actions = action.elseActions,
        onUpdate = { newList -> onUpdate(action.copy(elseActions = newList)) },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_if_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun UnlockScreenFields(
    action: Action.UnlockScreen,
    onUpdate: (Action) -> Unit,
) {
    LongField(
        label = "Timeout (ms)",
        value = action.timeoutMs,
        tag = "action_unlock_timeout",
        onChange = { onUpdate(action.copy(timeoutMs = it.coerceIn(1_000L, 15_000L))) },
    )
    Text(
        "Wakes the screen and dismisses the keyguard. Works automatically only when no " +
            "PIN/pattern/password is configured. If a credential is set, Android will show its " +
            "unlock prompt and the user must enter it within the timeout — there is no public " +
            "API to bypass a configured credential.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_unlock_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun TryCatchFields(
    action: Action.TryCatch,
    onUpdate: (Action) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_try_intoerrorvar"),
        value = action.intoErrorVar,
        onValueChange = { onUpdate(action.copy(intoErrorVar = it)) },
        label = { Text("Error variable name (optional)") },
        singleLine = true,
        textStyle = bodyMonospace,
        supportingText = {
            Text(
                "When the try path fails, the engine writes a short reason here for the catch " +
                    "actions to read via {var.<name>}. Blank = no capture.",
            )
        },
    )
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    NestedBranch(
        branchLabel = "Try",
        branchTag = "try",
        actions = action.tryActions,
        onUpdate = { newList -> onUpdate(action.copy(tryActions = newList)) },
    )
    Spacer(Modifier.height(12.dp))
    NestedBranch(
        branchLabel = "Catch (runs on try failure)",
        branchTag = "catch",
        actions = action.catchActions,
        onUpdate = { newList -> onUpdate(action.copy(catchActions = newList)) },
    )
    Spacer(Modifier.height(12.dp))
    NestedBranch(
        branchLabel = "Finally (always runs)",
        branchTag = "finally",
        actions = action.finallyActions,
        onUpdate = { newList -> onUpdate(action.copy(finallyActions = newList)) },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_try_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun LoopFields(
    action: Action.Loop,
    error: String?,
    onUpdate: (Action) -> Unit,
) {
    EnumDropdown(
        tag = "action_loop_mode",
        label = "Mode",
        values = com.flowdroid.common.flow.LoopMode.entries.toList(),
        selected = action.mode,
        toLabel = {
            when (it) {
                com.flowdroid.common.flow.LoopMode.COUNT -> "Repeat N times"
                com.flowdroid.common.flow.LoopMode.FOREACH_LINES -> "Foreach line of value"
            }
        },
        onSelect = { onUpdate(action.copy(mode = it)) },
    )
    Spacer(Modifier.height(8.dp))
    when (action.mode) {
        com.flowdroid.common.flow.LoopMode.COUNT -> {
            val (text, onChange) = rememberIntDigitState(action.count) {
                onUpdate(action.copy(count = it.coerceIn(0, Action.Loop.MAX_ITERATIONS)))
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("action_loop_count"),
                value = text,
                onValueChange = onChange,
                label = { Text("Count (0..${Action.Loop.MAX_ITERATIONS})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        }
        com.flowdroid.common.flow.LoopMode.FOREACH_LINES -> {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("action_loop_list"),
                value = action.list,
                onValueChange = { onUpdate(action.copy(list = it)) },
                label = { Text("Source (newline-separated, magic-text)") },
                minLines = 2,
                maxLines = 6,
                textStyle = bodyMonospace,
                isError = error != null,
                supportingText = error?.let { { Text(it) } }
                    ?: { Text("e.g. {var.responseBody} after an HTTP action") },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_loop_itemvar"),
        value = action.itemVar,
        onValueChange = { onUpdate(action.copy(itemVar = it)) },
        label = { Text("Item variable name") },
        singleLine = true,
        textStyle = bodyMonospace,
        supportingText = { Text("Used as {var.${action.itemVar.ifBlank { "item" }}} inside the loop body") },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("action_loop_indexvar"),
        value = action.indexVar,
        onValueChange = { onUpdate(action.copy(indexVar = it)) },
        label = { Text("Index variable name") },
        singleLine = true,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    NestedBranch(
        branchLabel = "Body",
        branchTag = "body",
        actions = action.actions,
        onUpdate = { newList -> onUpdate(action.copy(actions = newList)) },
    )
    Spacer(Modifier.height(8.dp))
    ContinueOnErrorSwitch(action.continueOnError, "action_loop_continue") {
        onUpdate(action.copy(continueOnError = it))
    }
}

@Composable
private fun NestedBranch(
    branchLabel: String,
    branchTag: String,
    actions: List<Action>,
    onUpdate: (List<Action>) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    Text(
        branchLabel,
        modifier = Modifier.testTag("action_if_${branchTag}_header"),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    if (actions.isEmpty()) {
        Text(
            "(no actions)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        actions.forEachIndexed { index, nested ->
            NestedActionCard(
                index = index,
                branchTag = branchTag,
                action = nested,
                onUpdate = { updated ->
                    onUpdate(actions.toMutableList().also { it[index] = updated })
                },
                onDelete = {
                    onUpdate(actions.toMutableList().also { it.removeAt(index) })
                },
            )
            Spacer(Modifier.height(6.dp))
        }
    }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        modifier = Modifier.testTag("action_if_${branchTag}_add"),
        onClick = { showSheet = true },
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(" Add to $branchLabel")
    }

    if (showSheet) {
        AddActionSheet(
            onPick = { picked ->
                onUpdate(actions + picked)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun NestedActionCard(
    index: Int,
    branchTag: String,
    action: Action,
    onUpdate: (Action) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(action::class) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("action_if_${branchTag}_card_$index"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        actionTypeLabel(action),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        summarizeAction(action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                TextButton(
                    modifier = Modifier.testTag("action_if_${branchTag}_card_${index}_toggle"),
                    onClick = { expanded = !expanded },
                ) { Text(if (expanded) "Done" else "Edit") }
                TextButton(
                    modifier = Modifier.testTag("action_if_${branchTag}_card_${index}_delete"),
                    onClick = onDelete,
                ) { Text("Delete") }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                // Re-use the same ActionFields switch the top-level editor uses. Recursive — an If
                // inside an If renders its own NestedBranches; depth is bounded by what the user
                // can usefully build.
                when (action) {
                    is Action.ClickNotificationAction -> ClickActionFields(action, null, onUpdate)
                    is Action.LaunchApp -> LaunchAppFields(action, null, onUpdate)
                    is Action.PostNotification -> PostNotificationFields(action, null, onUpdate)
                    is Action.Delay -> DelayFields(action, null, onUpdate)
                    is Action.KillApp -> KillAppFields(action, null, onUpdate)
                    is Action.UiClick -> UiClickFields(action, null, onUpdate)
                    is Action.UiSwipe -> UiSwipeFields(action, null, onUpdate)
                    is Action.UiTypeText -> UiTypeTextFields(action, null, onUpdate)
                    is Action.UiPressKey -> UiPressKeyFields(action, onUpdate)
                    is Action.Http -> HttpActionFields(action, null, onUpdate)
                    is Action.SetVariable -> SetVariableFields(action, null, onUpdate)
                    is Action.ReadFile -> ReadFileFields(action, null, onUpdate)
                    is Action.WriteFile -> WriteFileFields(action, null, onUpdate)
                    is Action.Base64 -> Base64Fields(action, null, onUpdate)
                    is Action.Hash -> HashFields(action, null, onUpdate)
                    is Action.If -> IfFields(action, null, onUpdate)
                    is Action.Loop -> LoopFields(action, null, onUpdate)
                    is Action.TryCatch -> TryCatchFields(action, onUpdate)
                    is Action.UnlockScreen -> UnlockScreenFields(action, onUpdate)
                    else -> Text("Unsupported action type")
                }
            }
        }
    }
}

private fun humanCompareOp(op: com.flowdroid.common.flow.CompareOp): String = when (op) {
    com.flowdroid.common.flow.CompareOp.EQUALS -> "equals"
    com.flowdroid.common.flow.CompareOp.NOT_EQUALS -> "not equals"
    com.flowdroid.common.flow.CompareOp.CONTAINS -> "contains"
    com.flowdroid.common.flow.CompareOp.STARTS_WITH -> "starts with"
    com.flowdroid.common.flow.CompareOp.ENDS_WITH -> "ends with"
    com.flowdroid.common.flow.CompareOp.REGEX -> "matches regex"
    com.flowdroid.common.flow.CompareOp.GT -> "greater than (number)"
    com.flowdroid.common.flow.CompareOp.LT -> "less than (number)"
    com.flowdroid.common.flow.CompareOp.GTE -> "greater or equal (number)"
    com.flowdroid.common.flow.CompareOp.LTE -> "less or equal (number)"
    com.flowdroid.common.flow.CompareOp.IS_BLANK -> "is blank"
    com.flowdroid.common.flow.CompareOp.IS_NOT_BLANK -> "is not blank"
}

/** Family-colored circular badge shown at the top-left of every action card. */
@Composable
private fun FamilyNode(color: Color, glyph: String) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color = color, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.background)
    }
}

/**
 * Small pill that shows the action's 1-based index. Background is the family color at low
 * opacity so it reads as "this card's family" without dominating the header.
 */
@Composable
private fun IndexPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .height(22.dp)
            .background(color = color.copy(alpha = 0.20f), shape = RoundedCornerShape(11.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ContinueOnErrorSwitch(checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Continue on error", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            modifier = Modifier.testTag(tag),
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}

/**
 * Editable numeric text field with local-typing state so clearing the field shows "" instead of
 * snapping back to "0".
 *
 * UX contract:
 *  - The user can fully erase the field; the visible value goes to "" and we do NOT push 0 to the
 *    model — the model keeps its last good value until the user types a new valid number.
 *  - On every keystroke that produces a valid float, we push that value to the model.
 *  - If the model changes externally (initial load, undo, another widget mutating the same
 *    field) AND the local text doesn't already reflect it, we resync the local text from the
 *    model. The heuristic skips resync when the user is mid-edit on an empty field.
 *
 * The same pattern is used by [LongField] below.
 */
@Composable
private fun FloatField(
    label: String,
    value: Float,
    tag: String,
    onChange: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(if (value == 0f) "" else value.toString()) }
    LaunchedEffect(value) {
        if (text.isNotEmpty() && text.toFloatOrNull() != value) {
            text = if (value == 0f) "" else value.toString()
        }
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        value = text,
        onValueChange = { raw ->
            text = raw
            raw.toFloatOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LongField(
    label: String,
    value: Long,
    tag: String,
    onChange: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.isNotEmpty() && text.toLongOrNull() != value) {
            text = value.toString()
        }
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }
            text = filtered
            filtered.toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * Local-text helper for inline-declared integer fields. Same UX contract as [LongField] /
 * [FloatField]: clearing the field shows "" instead of "0", and the model only updates on a
 * keystroke that produces a parseable integer. Returns `(text, onValueChange)` for the caller
 * to wire into its OutlinedTextField — needed because inline fields have call-site-specific
 * `label` / `supportingText` / Row weights that don't fit a generic helper signature.
 */
@Composable
private fun rememberIntDigitState(
    value: Int,
    onChange: (Int) -> Unit,
): Pair<String, (String) -> Unit> {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.isNotEmpty() && text.toIntOrNull() != value) {
            text = value.toString()
        }
    }
    val onValueChange: (String) -> Unit = { raw ->
        val filtered = raw.filter { it.isDigit() }
        text = filtered
        filtered.toIntOrNull()?.let(onChange)
    }
    return text to onValueChange
}

/** Long-typed sibling of [rememberIntDigitState]. */
@Composable
private fun rememberLongDigitState(
    value: Long,
    onChange: (Long) -> Unit,
): Pair<String, (String) -> Unit> {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.isNotEmpty() && text.toLongOrNull() != value) {
            text = value.toString()
        }
    }
    val onValueChange: (String) -> Unit = { raw ->
        val filtered = raw.filter { it.isDigit() }
        text = filtered
        filtered.toLongOrNull()?.let(onChange)
    }
    return text to onValueChange
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
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
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
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

private fun humanTargetMode(m: UiTargetMode): String = when (m) {
    UiTargetMode.COORDINATES -> "Coordinates"
    UiTargetMode.BY_TEXT -> "By text"
    UiTargetMode.BY_CONTENT_DESCRIPTION -> "By content description"
    UiTargetMode.BY_VIEW_ID -> "By view id"
}

private fun actionTypeLabel(action: Action): String = when (action) {
    is Action.ClickNotificationAction -> "Click notification button"
    is Action.LaunchApp -> "Launch app"
    is Action.PostNotification -> "Post notification"
    is Action.Delay -> "Delay"
    is Action.KillApp -> "Kill app"
    is Action.UiClick -> "UI tap"
    is Action.UiSwipe -> "UI swipe"
    is Action.UiTypeText -> "Type text"
    is Action.UiPressKey -> "Press key"
    is Action.Http -> "HTTP request"
    is Action.SetVariable -> "Set variable"
    is Action.ReadFile -> "Read file"
    is Action.WriteFile -> "Write file"
    is Action.Base64 -> "Base64"
    is Action.Hash -> "Hash"
    is Action.If -> "If / Else"
    is Action.Loop -> "Loop / Foreach"
    is Action.TryCatch -> "Try / Catch / Finally"
    is Action.UnlockScreen -> "Unlock screen"
    else -> "Custom action"
}

/**
 * `.copy(label = …)` for any [Action] variant. Each variant is a data class with its own
 * generated copy, but Kotlin doesn't unify them via the interface — hence this manual switch.
 */
private fun Action.withLabel(label: String?): Action = when (this) {
    is Action.ClickNotificationAction -> copy(label = label)
    is Action.LaunchApp -> copy(label = label)
    is Action.PostNotification -> copy(label = label)
    is Action.Delay -> copy(label = label)
    is Action.KillApp -> copy(label = label)
    is Action.UiClick -> copy(label = label)
    is Action.UiSwipe -> copy(label = label)
    is Action.UiTypeText -> copy(label = label)
    is Action.UiPressKey -> copy(label = label)
    is Action.Http -> copy(label = label)
    is Action.SetVariable -> copy(label = label)
    is Action.ReadFile -> copy(label = label)
    is Action.WriteFile -> copy(label = label)
    is Action.Base64 -> copy(label = label)
    is Action.Hash -> copy(label = label)
    is Action.If -> copy(label = label)
    is Action.Loop -> copy(label = label)
    is Action.TryCatch -> copy(label = label)
    is Action.UnlockScreen -> copy(label = label)
    else -> this
}

private fun triggerTypeLabel(trigger: Trigger): String = when (trigger) {
    is Trigger.NotificationPosted -> "Notification posted"
    is Trigger.TimeOfDay -> "Time of day"
    is Trigger.Interval -> "Interval"
    is Trigger.Webhook -> "Webhook"
    else -> "Custom trigger"
}

private fun Trigger.withLabel(label: String?): Trigger = when (this) {
    is Trigger.NotificationPosted -> copy(label = label)
    is Trigger.TimeOfDay -> copy(label = label)
    is Trigger.Interval -> copy(label = label)
    is Trigger.Webhook -> copy(label = label)
    else -> this
}

private fun triggerCatalogIdFor(trigger: Trigger): String = when (trigger) {
    is Trigger.NotificationPosted -> "notification_posted"
    is Trigger.TimeOfDay -> "time_of_day"
    is Trigger.Interval -> "interval"
    is Trigger.Webhook -> "webhook"
    else -> ""
}

@Composable
private fun TriggerTypeDropdown(
    index: Int,
    current: Trigger,
    onPick: (TriggerCatalog.TriggerEntry) -> Unit,
) {
    val currentId = triggerCatalogIdFor(current)
    val selected = TriggerCatalog.triggers.firstOrNull { it.id == currentId }
        ?: TriggerCatalog.triggers.first()
    EnumDropdown(
        tag = "flow_editor_trigger_${index}_type",
        label = "Type",
        values = TriggerCatalog.triggers,
        selected = selected,
        toLabel = { it.name },
        onSelect = { entry ->
            if (entry.id != currentId) onPick(entry)
        },
    )
}

@Composable
private fun TimeOfDayTriggerFields(
    index: Int,
    trigger: Trigger.TimeOfDay,
    onUpdate: (Trigger) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (hourText, onHourChange) = rememberIntDigitState(trigger.hour) {
            onUpdate(trigger.copy(hour = it.coerceIn(0, 23)))
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("flow_editor_trigger_${index}_hour"),
            value = hourText,
            onValueChange = onHourChange,
            label = { Text("Hour (0–23)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(0.dp))
        val (minuteText, onMinuteChange) = rememberIntDigitState(trigger.minute) {
            onUpdate(trigger.copy(minute = it.coerceIn(0, 59)))
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("flow_editor_trigger_${index}_minute"),
            value = minuteText,
            onValueChange = onMinuteChange,
            label = { Text("Minute (0–59)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Days of week (none selected = every day)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in trigger.daysOfWeek
            FilterChip(
                modifier = Modifier.testTag("flow_editor_trigger_${index}_day_${day.name}"),
                selected = selected,
                onClick = {
                    val next = trigger.daysOfWeek.toMutableSet().apply {
                        if (selected) remove(day) else add(day)
                    }
                    onUpdate(trigger.copy(daysOfWeek = next))
                },
                label = { Text(day.name) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun IntervalTriggerFields(
    index: Int,
    trigger: Trigger.Interval,
    onUpdate: (Trigger) -> Unit,
) {
    val (intervalText, onIntervalChange) = rememberIntDigitState(trigger.intervalMinutes) {
        onUpdate(trigger.copy(intervalMinutes = it.coerceIn(1, 1440)))
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("flow_editor_trigger_${index}_interval"),
        value = intervalText,
        onValueChange = onIntervalChange,
        label = { Text("Interval (minutes, 1–1440)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        supportingText = {
            Text("< 15 min uses AlarmManager (may be throttled on some OEMs).")
        },
    )
    Spacer(Modifier.height(8.dp))
    val windowOn = trigger.activeWindow != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Active window?", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            modifier = Modifier.testTag("flow_editor_trigger_${index}_window_switch"),
            checked = windowOn,
            onCheckedChange = { on ->
                onUpdate(
                    trigger.copy(
                        activeWindow = if (on) TimeRange(8, 0, 18, 0) else null,
                    ),
                )
            },
        )
    }
    if (windowOn) {
        val w = trigger.activeWindow!!
        Spacer(Modifier.height(8.dp))
        Text("Start", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (sh, onSh) = rememberIntDigitState(w.startHour) {
                onUpdate(trigger.copy(activeWindow = w.copy(startHour = it.coerceIn(0, 23))))
            }
            OutlinedTextField(
                modifier = Modifier.weight(1f).testTag("flow_editor_trigger_${index}_window_start_hour"),
                value = sh,
                onValueChange = onSh,
                label = { Text("HH") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(0.dp))
            val (sm, onSm) = rememberIntDigitState(w.startMinute) {
                onUpdate(trigger.copy(activeWindow = w.copy(startMinute = it.coerceIn(0, 59))))
            }
            OutlinedTextField(
                modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("flow_editor_trigger_${index}_window_start_min"),
                value = sm,
                onValueChange = onSm,
                label = { Text("MM") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("End", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (eh, onEh) = rememberIntDigitState(w.endHour) {
                onUpdate(trigger.copy(activeWindow = w.copy(endHour = it.coerceIn(0, 23))))
            }
            OutlinedTextField(
                modifier = Modifier.weight(1f).testTag("flow_editor_trigger_${index}_window_end_hour"),
                value = eh,
                onValueChange = onEh,
                label = { Text("HH") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(0.dp))
            val (em, onEm) = rememberIntDigitState(w.endMinute) {
                onUpdate(trigger.copy(activeWindow = w.copy(endMinute = it.coerceIn(0, 59))))
            }
            OutlinedTextField(
                modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("flow_editor_trigger_${index}_window_end_min"),
                value = em,
                onValueChange = onEm,
                label = { Text("MM") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun WebhookTriggerFields(
    index: Int,
    trigger: Trigger.Webhook,
    onUpdate: (Trigger) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val fullUrl = "http://127.0.0.1:8821/hook/${trigger.path}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f).testTag("flow_editor_trigger_${index}_path"),
            value = trigger.path,
            onValueChange = {},
            readOnly = true,
            label = { Text("Path") },
            singleLine = true,
            textStyle = bodyMonospace,
        )
        OutlinedButton(
            modifier = Modifier.padding(start = 8.dp).testTag("flow_editor_trigger_${index}_regenerate"),
            onClick = {
                val newPath = "hook-" + (1..8).map { "0123456789abcdef".random() }.joinToString("")
                onUpdate(trigger.copy(path = newPath))
            },
        ) { Text("Regenerate") }
    }
    Spacer(Modifier.height(8.dp))
    EnumDropdown(
        tag = "flow_editor_trigger_${index}_method",
        label = "Method",
        values = WebhookMethod.values().toList(),
        selected = trigger.method,
        toLabel = { it.name },
        onSelect = { onUpdate(trigger.copy(method = it)) },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().testTag("flow_editor_trigger_${index}_secret"),
        value = trigger.secret.orEmpty(),
        onValueChange = { onUpdate(trigger.copy(secret = it.ifBlank { null })) },
        label = { Text("Secret (optional — sent as X-FlowDroid-Secret)") },
        singleLine = true,
        textStyle = bodyMonospace,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            fullUrl,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            modifier = Modifier.testTag("flow_editor_trigger_${index}_copy_url"),
            onClick = { clipboard.setText(AnnotatedString(fullUrl)) },
        ) { Text("Copy URL") }
    }
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTriggerSheet(
    onPick: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) { TriggerCatalog.searchTriggers(query) }
    val grouped = remember(filtered) { filtered.groupBy { it.family } }

    ModalBottomSheet(
        modifier = Modifier.testTag("flow_editor_add_trigger_sheet"),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Add trigger",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("flow_editor_add_trigger_sheet_search"),
                singleLine = true,
                placeholder = { Text("Search triggers…") },
            )
            Spacer(Modifier.height(8.dp))

            if (grouped.isEmpty()) {
                Text(
                    "No triggers match \"$query\".",
                    modifier = Modifier.padding(16.dp).testTag("flow_editor_add_trigger_sheet_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TriggerCatalog.TriggerFamily.entries.forEach { family ->
                    val entries = grouped[family] ?: return@forEach
                    FamilyHeader(family.label, "trigger_family_${family.name}")
                    entries.forEach { entry ->
                        ListItem(
                            modifier = Modifier.testTag("add_trigger_${entry.id}"),
                            headlineContent = { Text(entry.name) },
                            supportingContent = { Text(entry.description) },
                            trailingContent = {
                                TextButton(
                                    modifier = Modifier.testTag("add_trigger_${entry.id}_pick"),
                                    onClick = { onPick(entry.factory()) },
                                ) { Text("Add") }
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddActionSheet(
    onPick: (Action) -> Unit,
    onDismiss: () -> Unit,
) {
    // skipPartiallyExpanded = true → the sheet opens full-height immediately, so all entries
    // (NETWORK + VARIABLES families included) are accessible without needing to drag up.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) { ActionCatalog.searchActions(query) }
    val grouped = remember(filtered) { filtered.groupBy { it.family } }

    ModalBottomSheet(
        modifier = Modifier.testTag("flow_editor_add_sheet"),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // Vertically scrollable so families below the fold remain reachable on small screens.
        Column(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Add action",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("flow_editor_add_sheet_search"),
                singleLine = true,
                placeholder = { Text("Search actions…") },
            )
            Spacer(Modifier.height(8.dp))

            if (grouped.isEmpty()) {
                Text(
                    "No actions match \"$query\".",
                    modifier = Modifier.padding(16.dp).testTag("flow_editor_add_sheet_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Iterate families in catalog order so the UI is stable.
                ActionCatalog.ActionFamily.entries.forEach { family ->
                    val entries = grouped[family] ?: return@forEach
                    FamilyHeader(family.label, "family_${family.name}")
                    entries.forEach { entry ->
                        ListItem(
                            modifier = Modifier.testTag("add_action_${entry.id}"),
                            headlineContent = { Text(entry.name) },
                            supportingContent = { Text(entry.description) },
                            trailingContent = {
                                TextButton(
                                    modifier = Modifier.testTag("add_action_${entry.id}_pick"),
                                    onClick = { onPick(entry.factory()) },
                                ) { Text("Add") }
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FamilyHeader(label: String, testTagSuffix: String) {
    Text(
        text = label.uppercase(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .testTag(testTagSuffix),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// --- Previews -----------------------------------------------------------------

private val SampleDraft = FlowDraft(
    name = "Echo notification",
    enabled = true,
    triggers = listOf(
        Trigger.NotificationPosted(
            packageName = "com.example.app",
            titleRegex = "^Echo.*",
            actionLabelRegex = "^Acknowledge$",
        ),
    ),
    actions = listOf(
        Action.ClickNotificationAction(labelRegex = "^Acknowledge$", dismissAfter = true),
        Action.PostNotification(title = "Acknowledged", text = "Echo notification handled"),
    ),
)

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun FlowEditorReadyPreview() {
    FlowDroidTheme {
        FlowEditorScreen(
            state = FlowEditorUiState(draft = SampleDraft),
            isNew = false,
            onBack = {},
            onSave = {},
            onSetName = {},
            onSetEnabled = {},
            onAddTrigger = { _ -> },
            onUpdateTrigger = { _, _ -> },
            onRemoveTrigger = {},
            onAddAction = {},
            onUpdateAction = { _, _ -> },
            onRemoveAction = {},
        )
    }
}

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun FlowEditorEmptyPreview() {
    FlowDroidTheme {
        FlowEditorScreen(
            state = FlowEditorUiState(
                draft = FlowDraft(
                    name = "",
                    enabled = true,
                    triggers = listOf(Trigger.NotificationPosted()),
                    actions = listOf(Action.ClickNotificationAction(labelRegex = "")),
                ),
                errors = EditorErrors(nameError = "Name is required"),
            ),
            isNew = true,
            onBack = {},
            onSave = {},
            onSetName = {},
            onSetEnabled = {},
            onAddTrigger = { _ -> },
            onUpdateTrigger = { _, _ -> },
            onRemoveTrigger = {},
            onAddAction = {},
            onUpdateAction = { _, _ -> },
            onRemoveAction = {},
        )
    }
}
