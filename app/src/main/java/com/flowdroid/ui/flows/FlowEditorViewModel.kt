package com.flowdroid.ui.flows

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.common.flow.Trigger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives the Flow editor screen. Holds an in-memory editable draft of a [Flow], tracks
 * dirty state, validates, and persists via [FlowRepository.upsert].
 *
 * Phase 2 widens the draft to hold a *list* of triggers (any of which fires the flow).
 * For Phase 2 the only concrete trigger type is still [Trigger.NotificationPosted] —
 * the editor's "Add trigger" only offers that — but the data shape and ViewModel API are
 * already prepared for future trigger kinds.
 */
@HiltViewModel
class FlowEditorViewModel @Inject constructor(
    private val repository: FlowRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Optional ID from the nav arg "flowId". Null for a fresh flow. */
    private val flowId: String? = savedStateHandle.get<String>("flowId")
        ?.takeIf { it.isNotBlank() && it != "new" }

    private val _state = MutableStateFlow(initialDraft(flowId))
    val state: StateFlow<FlowEditorUiState> = _state.asStateFlow()

    init {
        if (flowId != null) loadExisting(flowId)
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            when (val outcome = repository.get(id)) {
                is Outcome.Ok -> {
                    val loaded = outcome.value
                    if (loaded == null) {
                        Timber.w("FlowEditor: flow id=$id not found, falling back to blank")
                        _state.value = FlowEditorUiState(
                            draft = blankDraft(),
                            existingId = null,
                            isLoading = false,
                            loadError = "Flow not found.",
                        )
                    } else {
                        _state.value = FlowEditorUiState(
                            draft = loaded.toDraft(),
                            existingId = loaded.id,
                            createdAt = loaded.createdAt,
                            isLoading = false,
                        )
                    }
                }
                is Outcome.Err -> {
                    Timber.w("FlowEditor: load failed: ${outcome.error}")
                    _state.value = _state.value.copy(
                        isLoading = false,
                        loadError = outcome.error.toString(),
                    )
                }
            }
        }
    }

    fun setName(name: String) {
        _state.update {
            it.copy(
                draft = it.draft.copy(name = name),
                isDirty = true,
                errors = it.errors.copy(nameError = null),
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.update {
            it.copy(draft = it.draft.copy(enabled = enabled), isDirty = true)
        }
    }

    /**
     * Append a new trigger to the draft. Defaults to [Trigger.NotificationPosted] so existing
     * call sites keep working; the picker sheet passes the user-chosen kind via the parameter.
     */
    fun addTrigger(trigger: Trigger = Trigger.NotificationPosted()) {
        _state.update {
            it.copy(
                draft = it.draft.copy(triggers = it.draft.triggers + trigger),
                isDirty = true,
            )
        }
    }

    /** Replace the trigger at [index]. No-op if out of range. */
    fun updateTriggerAt(index: Int, trigger: Trigger) {
        _state.update {
            val list = it.draft.triggers.toMutableList()
            if (index in list.indices) {
                list[index] = trigger
                it.copy(
                    draft = it.draft.copy(triggers = list.toList()),
                    isDirty = true,
                    errors = it.errors.copy(triggerWarnings = computeTriggerWarnings(list)),
                )
            } else it
        }
    }

    /**
     * Remove the trigger at [index]. Refuses (no-op) when only one trigger remains —
     * the UI must reflect this with a disabled state. Cannot drop below one trigger.
     */
    fun removeTriggerAt(index: Int) {
        _state.update {
            val list = it.draft.triggers.toMutableList()
            if (list.size <= 1) return@update it
            if (index in list.indices) {
                list.removeAt(index)
                it.copy(
                    draft = it.draft.copy(triggers = list.toList()),
                    isDirty = true,
                    errors = it.errors.copy(triggerWarnings = computeTriggerWarnings(list)),
                )
            } else it
        }
    }

    /**
     * Phase 1 single-trigger compatibility shim — `state.draft.trigger` is still the typed
     * NotificationPosted for the legacy code path. Replaces trigger 0 in the list.
     */
    fun setTrigger(trigger: Trigger.NotificationPosted) = updateTriggerAt(0, trigger)

    fun addAction(action: Action) {
        _state.update {
            it.copy(
                draft = it.draft.copy(actions = it.draft.actions + action),
                isDirty = true,
            )
        }
    }

    fun updateActionAt(index: Int, action: Action) {
        _state.update {
            val list = it.draft.actions.toMutableList()
            if (index in list.indices) {
                list[index] = action
                it.copy(
                    draft = it.draft.copy(actions = list.toList()),
                    isDirty = true,
                    errors = it.errors.copy(
                        actionErrors = it.errors.actionErrors.toMutableMap().apply { remove(index) },
                    ),
                )
            } else it
        }
    }

    fun removeActionAt(index: Int) {
        _state.update {
            val list = it.draft.actions.toMutableList()
            if (index in list.indices) {
                list.removeAt(index)
                it.copy(
                    draft = it.draft.copy(actions = list.toList()),
                    isDirty = true,
                    collapsedActions = shiftIndicesAfterRemoval(it.collapsedActions, index),
                )
            } else it
        }
    }

    /**
     * Duplicate the action at [index], inserting the copy at `index + 1`. The user can then edit
     * one or the other independently — useful when building flows where two actions differ in a
     * single field (e.g. two `LaunchApp`s for sibling activities).
     */
    fun duplicateActionAt(index: Int) {
        _state.update {
            val list = it.draft.actions.toMutableList()
            if (index !in list.indices) return@update it
            // Actions are data classes — adding the same reference is fine since they're immutable,
            // but adding a defensive .copy() via reflection-free spread isn't possible. We rely on
            // immutability: inserting the same instance twice is safe in Kotlin.
            list.add(index + 1, list[index])
            it.copy(
                draft = it.draft.copy(actions = list.toList()),
                isDirty = true,
                collapsedActions = shiftIndicesAfterInsertion(it.collapsedActions, index + 1),
            )
        }
    }

    /**
     * Move the action at [from] to position [to]. No-op if either index is out of range or equal.
     * Called by the drag-reorder handler in the editor.
     */
    fun moveAction(from: Int, to: Int) {
        if (from == to) return
        _state.update {
            val list = it.draft.actions.toMutableList()
            if (from !in list.indices || to !in list.indices) return@update it
            val item = list.removeAt(from)
            list.add(to, item)
            // Remap collapsed indices through the move so the user's expand/collapse choices
            // travel with the item rather than getting wiped.
            //  - the item at `from` moves to `to` — keep its state
            //  - items in between shift by one (direction depends on move direction)
            //  - other items unaffected
            val newCollapsed = it.collapsedActions.map { i ->
                when {
                    i == from -> to
                    from < to && i in (from + 1)..to -> i - 1
                    from > to && i in to until from -> i + 1
                    else -> i
                }
            }.toSet()
            it.copy(
                draft = it.draft.copy(actions = list.toList()),
                isDirty = true,
                collapsedActions = newCollapsed,
            )
        }
    }

    /** Toggle the collapsed/expanded state of trigger at [index]. */
    fun toggleTriggerCollapsed(index: Int) {
        _state.update {
            val collapsed = it.collapsedTriggers.toMutableSet()
            if (index in collapsed) collapsed.remove(index) else collapsed.add(index)
            it.copy(collapsedTriggers = collapsed.toSet())
        }
    }

    /** Toggle the collapsed/expanded state of action at [index]. */
    fun toggleActionCollapsed(index: Int) {
        _state.update {
            val collapsed = it.collapsedActions.toMutableSet()
            if (index in collapsed) collapsed.remove(index) else collapsed.add(index)
            it.copy(collapsedActions = collapsed.toSet())
        }
    }

    private fun shiftIndicesAfterRemoval(collapsed: Set<Int>, removedIndex: Int): Set<Int> =
        collapsed.mapNotNull { i ->
            when {
                i == removedIndex -> null
                i > removedIndex -> i - 1
                else -> i
            }
        }.toSet()

    private fun shiftIndicesAfterInsertion(collapsed: Set<Int>, insertedIndex: Int): Set<Int> =
        collapsed.map { i -> if (i >= insertedIndex) i + 1 else i }.toSet()

    /**
     * Validate the current draft. On failure, updates the state with field errors and returns
     * the list. On success, persists via the repository and emits [SaveResult.Success].
     */
    suspend fun save(): SaveResult {
        val draft = _state.value.draft
        val errors = validate(draft)
        if (errors.hasAny()) {
            _state.update { it.copy(errors = errors) }
            return SaveResult.Invalid(errors)
        }
        val now = clock.nowMillis()
        val id = _state.value.existingId ?: UUID.randomUUID().toString()
        val createdAt = _state.value.createdAt ?: now
        val flow = Flow(
            id = id,
            name = draft.name.trim(),
            enabled = draft.enabled,
            triggers = draft.triggers,
            actions = draft.actions,
            createdAt = createdAt,
            updatedAt = now,
        )
        return when (val outcome = repository.upsert(flow)) {
            is Outcome.Ok -> {
                _state.update {
                    it.copy(
                        existingId = id,
                        createdAt = createdAt,
                        isDirty = false,
                        errors = EditorErrors(),
                        saveError = null,
                    )
                }
                SaveResult.Success(id)
            }
            is Outcome.Err -> {
                val msg = outcome.error.toString()
                Timber.w("FlowEditor: upsert failed: $msg")
                _state.update { it.copy(saveError = msg) }
                SaveResult.PersistFailed(msg)
            }
        }
    }

    private fun validate(draft: FlowDraft): EditorErrors {
        val nameError = if (draft.name.isBlank()) "Name is required" else null
        val triggersError = if (draft.triggers.isEmpty()) "At least one trigger is required" else null
        val actionErrors = mutableMapOf<Int, String>()
        draft.actions.forEachIndexed { i, a -> validateAction(a)?.let { actionErrors[i] = it } }
        return EditorErrors(
            nameError = nameError,
            triggersError = triggersError,
            triggerWarnings = computeTriggerWarnings(draft.triggers),
            actionErrors = actionErrors,
        )
    }

    private fun validateAction(action: Action): String? = when (action) {
        is Action.ClickNotificationAction ->
            if (action.labelRegex.isBlank()) "Label regex is required" else null
        is Action.LaunchApp ->
            if (action.packageName.isBlank()) "Package name is required" else null
        is Action.PostNotification ->
            if (action.title.isBlank() && action.text.isBlank()) "Title or text is required" else null
        is Action.Delay ->
            if (action.millis <= 0) "Duration must be > 0" else null
        is Action.KillApp ->
            if (action.packageName.isBlank()) "Package name is required" else null
        is Action.UiTypeText ->
            if (action.text.isEmpty()) "Text is required" else null
        is Action.ReadFile -> when {
            action.path.isBlank() -> "Path is required"
            action.intoVar.isBlank() -> "Variable name is required"
            action.path.trim().startsWith("/") -> "Path must be relative"
            action.path.split('/', '\\').any { it == ".." } -> "Path must not contain \"..\""
            else -> null
        }
        is Action.WriteFile -> when {
            action.path.isBlank() -> "Path is required"
            action.path.trim().startsWith("/") -> "Path must be relative"
            action.path.split('/', '\\').any { it == ".." } -> "Path must not contain \"..\""
            else -> null
        }
        is Action.Base64 ->
            if (action.intoVar.isBlank()) "Variable name is required" else null
        is Action.Hash ->
            if (action.intoVar.isBlank()) "Variable name is required" else null
        is Action.Loop -> when {
            action.mode == com.flowdroid.common.flow.LoopMode.COUNT && action.count < 0 ->
                "Count must be ≥ 0"
            else -> null
        }
        else -> null
    }

    private fun computeTriggerWarnings(triggers: List<Trigger>): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        triggers.forEachIndexed { i, t ->
            triggerWarning(t)?.let { out[i] = it }
        }
        return out
    }

    private fun triggerWarning(t: Trigger): String? = when (t) {
        is Trigger.NotificationPosted -> {
            val anyFilter = t.packageName != null ||
                t.titleRegex != null ||
                t.textRegex != null ||
                t.actionLabelRegex != null
            if (!anyFilter) "This trigger has no filters — it will match every notification."
            else null
        }
        else -> null
    }

    companion object {
        private fun initialDraft(existingId: String?): FlowEditorUiState = FlowEditorUiState(
            draft = blankDraft(),
            existingId = null,
            isLoading = existingId != null,
        )

        private fun blankDraft(): FlowDraft = FlowDraft(
            name = "",
            enabled = true,
            triggers = listOf(Trigger.NotificationPosted()),
            actions = listOf(Action.ClickNotificationAction(labelRegex = "")),
        )

        private fun Flow.toDraft(): FlowDraft = FlowDraft(
            name = name,
            enabled = enabled,
            triggers = triggers.ifEmpty { listOf(Trigger.NotificationPosted()) },
            actions = actions,
        )

        private fun EditorErrors.hasAny(): Boolean =
            nameError != null || triggersError != null || actionErrors.isNotEmpty()
    }
}

/** Editable in-memory representation of a Flow. */
data class FlowDraft(
    val name: String,
    val enabled: Boolean,
    val triggers: List<Trigger>,
    val actions: List<Action>,
) {
    /**
     * Compatibility shim for callers that still want the first NotificationPosted trigger as
     * a typed value (the editor previews and a couple of legacy paths). Returns the first
     * trigger if it is a NotificationPosted, else a fresh default.
     */
    val trigger: Trigger.NotificationPosted
        get() = (triggers.firstOrNull() as? Trigger.NotificationPosted)
            ?: Trigger.NotificationPosted()
}

/** Per-field validation errors (and per-index trigger warnings). */
data class EditorErrors(
    val nameError: String? = null,
    val triggersError: String? = null,
    val triggerWarnings: Map<Int, String> = emptyMap(),
    val actionErrors: Map<Int, String> = emptyMap(),
) {
    /** Convenience: the first trigger's warning, for legacy single-trigger UI bits. */
    val triggerWarning: String? get() = triggerWarnings[0]
}

/** Top-level UI state for the editor. */
data class FlowEditorUiState(
    val draft: FlowDraft,
    val existingId: String? = null,
    val createdAt: Long? = null,
    val isDirty: Boolean = false,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val saveError: String? = null,
    val errors: EditorErrors = EditorErrors(),
    /** Indices of triggers shown as a one-line summary instead of the full editable form. */
    val collapsedTriggers: Set<Int> = emptySet(),
    /** Indices of actions shown as a one-line summary instead of the full editable form. */
    val collapsedActions: Set<Int> = emptySet(),
)

sealed interface SaveResult {
    data class Success(val id: String) : SaveResult
    data class Invalid(val errors: EditorErrors) : SaveResult
    data class PersistFailed(val message: String) : SaveResult
}
