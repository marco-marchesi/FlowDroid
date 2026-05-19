package com.flowdroid.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowdroid.common.Clock
import com.flowdroid.common.HealthSnapshot
import com.flowdroid.common.domain.HealthEvent
import com.flowdroid.common.repo.HealthRepository
import com.flowdroid.common.repo.NotificationRepository
import com.flowdroid.common.service.ServiceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Observes the live [HealthSnapshot] from [ServiceRegistry] plus the recent disconnect journal
 * from [HealthRepository], merging them into one [HealthUiState] for the Health screen.
 *
 * Failure policy: any error inside the source flows is caught and turned into
 * [HealthUiState.Error]. The UI must remain navigable even when one repo is broken — the
 * user relies on this screen to diagnose problems, so showing a half-broken Health screen is
 * strictly better than crashing.
 */
@HiltViewModel
class HealthViewModel @Inject constructor(
    private val serviceRegistry: ServiceRegistry,
    private val healthRepository: HealthRepository,
    @Suppress("unused") private val notificationRepository: NotificationRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _events = MutableSharedFlow<HealthScreenEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val state: StateFlow<HealthUiState> = combine(
        serviceRegistry.observeSnapshot(),
        healthRepository.observeRecent(limit = 50),
    ) { snapshot, healthEvents ->
        val disconnects = healthEvents
            .filter { it.kind == HealthEvent.Kind.LISTENER_DISCONNECTED }
            .take(20)
        val rebinds = healthEvents.count { it.kind == HealthEvent.Kind.LISTENER_REBIND_REQUESTED }
        HealthUiState.Ready(
            snapshot = snapshot,
            disconnects = disconnects,
            rebindAttemptsLast24h = snapshot.rebindAttemptsLast24h,
            listenerDisconnectsLast24h = disconnects.size,
            notificationsReceivedLast24h = snapshot.notificationsReceivedLast24h,
            rebindsFromJournal = rebinds,
            nowMillis = clock.nowMillis(),
        ) as HealthUiState
    }
        .catch { t -> emit(HealthUiState.Error(message = t.message ?: t::class.simpleName.orEmpty())) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HealthUiState.Loading,
        )

    fun runSelfTest() {
        viewModelScope.launch {
            _events.emit(HealthScreenEvent.SelfTestRequested)
        }
    }
}

/** UI state for the Health screen. Stable so Compose can skip identical recompositions. */
sealed interface HealthUiState {
    data object Loading : HealthUiState

    data class Error(val message: String) : HealthUiState

    data class Ready(
        val snapshot: HealthSnapshot,
        val disconnects: List<HealthEvent>,
        val rebindAttemptsLast24h: Int,
        val listenerDisconnectsLast24h: Int,
        val notificationsReceivedLast24h: Int,
        val rebindsFromJournal: Int,
        val nowMillis: Long,
    ) : HealthUiState
}

/** One-shot events from the screen (currently just "Self-test pressed"). */
sealed interface HealthScreenEvent {
    data object SelfTestRequested : HealthScreenEvent
}
