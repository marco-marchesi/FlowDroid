package com.flowdroid.ui.flows

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowdroid.common.domain.FlowRun
import com.flowdroid.common.repo.FlowRunRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Observes the run history for a single flow. Pulls `flowId` from the navigation arguments via
 * [SavedStateHandle].
 */
@HiltViewModel
class FlowRunsViewModel @Inject constructor(
    private val flowRunRepo: FlowRunRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val flowId: String = savedStateHandle.get<String>("flowId").orEmpty()

    val state: StateFlow<FlowRunsUiState> = flowRunRepo
        .observeRecentForFlow(flowId, limit = 50)
        .map<List<FlowRun>, FlowRunsUiState> { runs ->
            if (runs.isEmpty()) FlowRunsUiState.Empty
            else FlowRunsUiState.Ready(runs)
        }
        .catch { t ->
            emit(FlowRunsUiState.Error(t.message ?: t::class.simpleName.orEmpty()))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FlowRunsUiState.Loading,
        )
}

sealed interface FlowRunsUiState {
    data object Loading : FlowRunsUiState
    data object Empty : FlowRunsUiState
    data class Ready(val runs: List<FlowRun>) : FlowRunsUiState
    data class Error(val message: String) : FlowRunsUiState
}
