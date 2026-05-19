package com.flowdroid.ui.flows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowdroid.common.Clock
import com.flowdroid.common.Outcome
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.FlowRepository
import com.flowdroid.data.flows.FlowImportExport
import com.flowdroid.data.flows.ImportError
import com.flowdroid.data.flows.TemplateGallery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Observes the persisted list of flows and exposes it as a [FlowsListUiState] for the
 * Flows list screen.
 *
 *  - Loading on first emission (initial value).
 *  - Empty when the repository returns no flows.
 *  - Ready with summaries otherwise.
 *  - Error if the upstream flow throws.
 */
@HiltViewModel
class FlowsListViewModel @Inject constructor(
    private val repository: FlowRepository,
    private val clock: Clock,
    private val templates: TemplateGallery,
) : ViewModel() {

    /** Read-only metadata list for the templates sheet — content stays in assets. */
    val availableTemplates: List<TemplateGallery.Template> get() = templates.templates

    val state: StateFlow<FlowsListUiState> = repository.observeAll()
        .map<List<Flow>, FlowsListUiState> { flows ->
            if (flows.isEmpty()) FlowsListUiState.Empty
            else FlowsListUiState.Ready(flows.map { it.toSummary() })
        }
        .catch { t ->
            Timber.w(t, "FlowsListViewModel upstream failed")
            emit(FlowsListUiState.Error(t.message ?: t::class.simpleName.orEmpty()))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FlowsListUiState.Loading,
        )

    /** Toggle the enabled flag for [id]. Errors are logged; the upstream flow drives UI. */
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            when (val outcome = repository.setEnabled(id, enabled)) {
                is Outcome.Ok -> Timber.d("setEnabled(id=$id, enabled=$enabled) -> rows=${outcome.value}")
                is Outcome.Err -> Timber.w("setEnabled(id=$id) failed: ${outcome.error}")
            }
        }
    }

    /** Delete the flow with [id]. Errors are logged; the upstream flow drives UI. */
    fun delete(id: String) {
        viewModelScope.launch {
            when (val outcome = repository.delete(id)) {
                is Outcome.Ok -> Timber.d("delete(id=$id) -> rows=${outcome.value}")
                is Outcome.Err -> Timber.w("delete(id=$id) failed: ${outcome.error}")
            }
        }
    }

    /**
     * Duplicate the flow with [id]. The copy is created disabled so it doesn't immediately race
     * the original. Errors are logged; the upstream flow re-emits with the new entry.
     */
    /**
     * Serialize the flow with [id] to a JSON string ready to write to a SAF document.
     * Returns null if the flow doesn't exist or the lookup failed (logged).
     */
    suspend fun exportFlowJson(id: String): String? {
        return when (val outcome = repository.get(id)) {
            is Outcome.Ok -> outcome.value?.let { FlowImportExport.exportFlow(it) }
            is Outcome.Err -> {
                Timber.w("exportFlowJson(id=$id) failed: ${outcome.error}")
                null
            }
        }
    }

    /** Serialize every flow currently in the repository to a single JSON document. */
    suspend fun exportAllFlowsJson(): String {
        val flows = repository.observeAll().first()
        return FlowImportExport.exportFlows(flows)
    }

    /**
     * Parse [json] and upsert each imported flow. Returns the outcome so the UI can show a
     * success snackbar with the count or a typed error message.
     */
    /**
     * Load a bundled template's JSON and route through the same import pipeline used by the
     * SAF picker. Returns the same [ImportResult] so the UI uses one snackbar code path for both
     * external imports and template installs.
     */
    suspend fun importTemplate(template: TemplateGallery.Template): ImportResult {
        val json = templates.load(template) ?: return ImportResult.Failed(
            ImportError.MalformedJson("template asset missing or unreadable"),
        )
        return importFlowsJson(json)
    }

    suspend fun importFlowsJson(json: String): ImportResult {
        return when (val parsed = FlowImportExport.importFlows(json, clock)) {
            is Outcome.Err -> ImportResult.Failed(parsed.error)
            is Outcome.Ok -> {
                var inserted = 0
                parsed.value.forEach { flow ->
                    when (val r = repository.upsert(flow)) {
                        is Outcome.Ok -> inserted++
                        is Outcome.Err -> Timber.w("importFlowsJson upsert failed: ${r.error}")
                    }
                }
                ImportResult.Success(inserted)
            }
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            when (val outcome = repository.duplicate(id, clock.nowMillis())) {
                is Outcome.Ok -> Timber.d("duplicate(id=$id) -> newId=${outcome.value}")
                is Outcome.Err -> Timber.w("duplicate(id=$id) failed: ${outcome.error}")
            }
        }
    }
}

private fun Flow.toSummary(): FlowListItem = FlowListItem(
    id = id,
    name = name,
    enabled = enabled,
    triggerSummary = summarizeTriggers(triggers),
    actionCount = actions.size,
    actionSummary = summarizeActions(actions),
)

/** One-row projection used by the list screen. */
data class FlowListItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val triggerSummary: String,
    val actionCount: Int,
    val actionSummary: String,
)

/** Result of [FlowsListViewModel.importFlowsJson], rendered as a snackbar by the screen. */
sealed interface ImportResult {
    data class Success(val count: Int) : ImportResult
    data class Failed(val error: ImportError) : ImportResult
}

sealed interface FlowsListUiState {
    data object Loading : FlowsListUiState
    data object Empty : FlowsListUiState
    data class Ready(val flows: List<FlowListItem>) : FlowsListUiState
    data class Error(val message: String) : FlowsListUiState
}
