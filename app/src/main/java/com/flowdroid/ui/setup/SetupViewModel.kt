package com.flowdroid.ui.setup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowdroid.common.Clock
import com.flowdroid.common.permission.DeepLinkTarget
import com.flowdroid.common.permission.OemBatteryHelper
import com.flowdroid.common.permission.OemBrand
import com.flowdroid.common.permission.PermissionChecker
import com.flowdroid.common.permission.PermissionSnapshot
import com.flowdroid.common.repo.NotificationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.flowdroid.data.settings.ConnectionSettingsRepository
import com.flowdroid.data.settings.MqttProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the Setup wizard. Wraps [PermissionChecker] for live state, [OemBatteryHelper] for
 * the OEM-specific row, and provides the self-test flow that posts a notification and waits
 * for the listener to echo it back.
 */
@HiltViewModel
class SetupViewModel(
    private val permissionChecker: PermissionChecker,
    private val oemBatteryHelper: OemBatteryHelper,
    private val notificationRepository: NotificationRepository,
    private val clock: Clock,
    private val selfTestNotifier: SelfTestNotifier,
    private val connectionSettings: ConnectionSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * Hilt-friendly constructor. Production graph has no `CoroutineDispatcher` binding, so the
     * primary ctor's default is the dispatcher production uses; tests can call the primary
     * ctor directly with a [kotlinx.coroutines.test.TestDispatcher].
     */
    @Inject constructor(
        permissionChecker: PermissionChecker,
        oemBatteryHelper: OemBatteryHelper,
        notificationRepository: NotificationRepository,
        clock: Clock,
        selfTestNotifier: SelfTestNotifier,
        connectionSettings: ConnectionSettingsRepository,
    ) : this(permissionChecker, oemBatteryHelper, notificationRepository, clock, selfTestNotifier, connectionSettings, Dispatchers.IO)

    private val brand: OemBrand = oemBatteryHelper.detect()

    val state: StateFlow<SetupUiState> = combine(
        permissionChecker.observe(),
        connectionSettings.mqttProfile,
    ) { snapshot, mqttProfile ->
        SetupUiState.Ready(snapshot, brand, mqttProfile) as SetupUiState
    }
        .catch { t -> emit(SetupUiState.Error(t.message ?: "Permission check failed")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState.Loading)

    fun refresh() {
        viewModelScope.launch { permissionChecker.refresh() }
    }

    fun saveMqttProfile(profile: MqttProfile) {
        viewModelScope.launch { connectionSettings.saveMqttProfile(profile) }
    }

    fun resolveDeepLink(target: DeepLinkTarget): android.content.Intent? =
        oemBatteryHelper.resolveDeepLink(target)

    /**
     * Posts a "FlowDroid self-test" notification and waits up to 3s for the notification
     * listener to echo it back via [NotificationRepository.observeRecent].
     *
     * The emitted flow is cold and one-shot: each subscription performs a fresh test.
     */
    fun runSelfTest(): Flow<SelfTestResult> = flow {
        emit(SelfTestResult.InProgress)
        val title = SelfTestNotifier.SELF_TEST_TITLE
        // Record the start of THIS test run BEFORE posting. We then accept a match only if the
        // captured event's postTimeMillis is at or after startMillis — otherwise a stale Room
        // row from a previous run would falsely report Success even when the listener is dead.
        val startMillis = clock.nowMillis()
        val postOutcome = selfTestNotifier.post(title)
        if (postOutcome is SelfTestPostOutcome.Failed) {
            emit(SelfTestResult.PostFailed(postOutcome.reason))
            return@flow
        }
        val match = withTimeoutOrNull(SELF_TEST_TIMEOUT_MILLIS) {
            notificationRepository.observeRecent(20)
                .filter { events ->
                    events.any { it.title == title && it.postTimeMillis >= startMillis }
                }
                .first()
        }
        if (match != null) emit(SelfTestResult.Success)
        else emit(SelfTestResult.TimeoutOrNotReceived)
    }.flowOn(ioDispatcher)

    companion object {
        const val SELF_TEST_TIMEOUT_MILLIS = 3_000L
    }
}

/**
 * Posts the self-test notification to the platform NotificationManager. Extracted as an
 * interface so unit tests can drive [SetupViewModel] without poking the real Android
 * notification system. Production code uses [DefaultSelfTestNotifier].
 */
interface SelfTestNotifier {
    fun post(title: String): SelfTestPostOutcome

    companion object {
        const val SELF_TEST_CHANNEL_ID = "flowdroid.selftest"
        const val SELF_TEST_NOTIF_ID = 0xF10D
        const val SELF_TEST_TITLE = "FlowDroid self-test"
    }
}

sealed interface SelfTestPostOutcome {
    data object Posted : SelfTestPostOutcome
    data class Failed(val reason: String) : SelfTestPostOutcome
}

@Singleton
class DefaultSelfTestNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : SelfTestNotifier {

    override fun post(title: String): SelfTestPostOutcome {
        return try {
            ensureChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return SelfTestPostOutcome.Failed("POST_NOTIFICATIONS not granted")
            }
            val notif = NotificationCompat.Builder(context, SelfTestNotifier.SELF_TEST_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText("Tap to confirm — auto-dismisses.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(SelfTestNotifier.SELF_TEST_NOTIF_ID, notif)
            SelfTestPostOutcome.Posted
        } catch (t: SecurityException) {
            SelfTestPostOutcome.Failed("SecurityException: ${t.message}")
        } catch (t: Exception) {
            SelfTestPostOutcome.Failed(t.message ?: t::class.simpleName.orEmpty())
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(SelfTestNotifier.SELF_TEST_CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                SelfTestNotifier.SELF_TEST_CHANNEL_ID,
                "FlowDroid self-test",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Used by the Setup wizard to verify the notification listener works."
            }
            nm.createNotificationChannel(ch)
        }
    }
}

/** State for the Setup screen. */
sealed interface SetupUiState {
    data object Loading : SetupUiState
    data class Error(val message: String) : SetupUiState
    data class Ready(
        val snapshot: PermissionSnapshot,
        val brand: OemBrand,
        val mqttProfile: MqttProfile = MqttProfile(),
    ) : SetupUiState
}

/** Outcome of the listener self-test. */
sealed interface SelfTestResult {
    data object InProgress : SelfTestResult
    data object Success : SelfTestResult
    data object TimeoutOrNotReceived : SelfTestResult
    data class PostFailed(val reason: String) : SelfTestResult
}

/** Hilt binding for the production [SelfTestNotifier] implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SetupUiModule {
    @Binds abstract fun bindSelfTestNotifier(impl: DefaultSelfTestNotifier): SelfTestNotifier
}
