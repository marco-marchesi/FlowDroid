package com.flowdroid.permission

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.permission.PermissionChecker
import com.flowdroid.common.permission.PermissionSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [PermissionChecker].
 *
 * Backed by a [MutableStateFlow] so observers see live updates whenever [refresh] is called
 * (typically from `onResume` of any wizard activity that just returned from a Settings page).
 *
 * Every system-API call here is wrapped in a try/catch that traps [SecurityException] and any
 * other unexpected throwable, logs it as WARN, and falls back to the safe default (`false` for
 * boolean grants). The whole subsystem must never crash the wizard — at worst we tell the user
 * "we don't know, please check manually".
 */
@Singleton
class PermissionCheckerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StructuredLogger,
    private val oemDetector: OemDetector,
) : PermissionChecker {

    /** Fully-qualified name of our notification listener service — declared in AndroidManifest.xml. */
    private val listenerComponent: ComponentName =
        ComponentName(context, "com.flowdroid.service.FlowDroidNotificationListenerService")

    private val state: MutableStateFlow<PermissionSnapshot> = MutableStateFlow(compute())

    /** Cold-but-conflated stream of the current snapshot. */
    override fun observe(): Flow<PermissionSnapshot> = state.asStateFlow()

    /** Last computed snapshot. Safe to call from any thread. */
    override fun snapshot(): PermissionSnapshot = state.value

    /** Recompute every facet and emit if the snapshot changed. */
    override fun refresh() {
        val next = compute()
        state.value = next
        logger.debug(
            "PermissionChecker",
            "refresh",
            "listener" to next.notificationListenerEnabled,
            "post" to next.postNotificationsGranted,
            "battery" to next.batteryOptimisationIgnored,
            "exactAlarm" to next.exactAlarmAllowed,
            "overlay" to next.overlayPermissionGranted,
            "accessibility" to next.accessibilityServiceEnabled,
        )
    }

    /**
     * Single source of truth for all permission facets. Pure function over the current device
     * state — no caching beyond what the system itself reports.
     */
    private fun compute(): PermissionSnapshot = PermissionSnapshot(
        notificationListenerEnabled = isNotificationListenerEnabled(),
        accessibilityServiceEnabled = isAccessibilityServiceEnabled(),
        postNotificationsGranted = isPostNotificationsGranted(),
        batteryOptimisationIgnored = isBatteryOptimisationIgnored(),
        exactAlarmAllowed = isExactAlarmAllowed(),
        overlayPermissionGranted = isOverlayPermissionGranted(),
        oemSpecific = safeOem(),
    )

    // ============================================================================ individual checks

    /**
     * True if our [FlowDroidNotificationListenerService][listenerComponent] appears in
     * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`.
     *
     * Parsing notes:
     *  - The setting is **colon-separated** between distinct enabled services, and the value
     *    for each service is a flattened ComponentName `pkg/service` (`pkg/.RelService` for
     *    relative-class shorthand). See `NotificationManagerService#isComponentEnabledForCurrentProfiles`
     *    in AOSP and Settings.Secure docs.
     *  - Empty string ⇒ no listeners enabled; `null` ⇒ same.
     *  - We tolerate both the canonical flattenToString (`pkg/full.qualified.Service`) and the
     *    short form (`pkg/.RelService`) by unflattening each token before comparison.
     */
    private fun isNotificationListenerEnabled(): Boolean = safeBool("notificationListener") {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return@safeBool false
        if (raw.isBlank()) return@safeBool false
        raw.split(':')
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == listenerComponent }
    }

    /**
     * True if any of our accessibility services is enabled. Phase 0 declares no
     * accessibility service so this is always false on a fresh install; the API exists so
     * the wizard can show the same green/red state once Phase 1+ adds one.
     *
     * Same colon-separated parsing rules as the notification listener setting.
     */
    private fun isAccessibilityServiceEnabled(): Boolean = safeBool("accessibilityService") {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return@safeBool false
        if (raw.isBlank()) return@safeBool false
        raw.split(':')
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == context.packageName }
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission introduced in API 33 (Tiramisu). On older
     * APIs notifications are granted at install time → return `true` unconditionally.
     */
    private fun isPostNotificationsGranted(): Boolean = safeBool("postNotifications") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@safeBool true
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * True if the user has whitelisted us from Doze / battery optimisation. Universal across
     * all API levels we support (min SDK is 29).
     */
    private fun isBatteryOptimisationIgnored(): Boolean = safeBool("batteryOptimisation") {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@safeBool false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * SCHEDULE_EXACT_ALARM became a user-revocable permission on API 31 (S). Below that, the
     * `<uses-permission>` declaration in the manifest is sufficient → always true.
     */
    private fun isExactAlarmAllowed(): Boolean = safeBool("exactAlarm") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@safeBool true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return@safeBool false
        am.canScheduleExactAlarms()
    }

    /**
     * SYSTEM_ALERT_WINDOW. We don't currently need it, but the wizard's "all permissions" page
     * still surfaces it so future overlay-using actions can be enabled in one place.
     */
    private fun isOverlayPermissionGranted(): Boolean = safeBool("overlay") {
        Settings.canDrawOverlays(context)
    }

    /** Defensive call into [OemDetector] — never let an unexpected throwable kill the snapshot. */
    private fun safeOem(): com.flowdroid.common.permission.OemSpecific = try {
        oemDetector.detect()
    } catch (t: Throwable) {
        if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
        logger.warn("PermissionChecker", "oemDetector failed", t)
        com.flowdroid.common.permission.OemSpecific.None
    }

    // =================================================================================== helpers

    /**
     * Run a boolean-returning block, swallowing [SecurityException] and any unexpected throwable
     * (except VM-level / cancellation) into a logged WARN + `false` return. This is the safety
     * net required by the project conventions.
     */
    private inline fun safeBool(facet: String, block: () -> Boolean): Boolean = try {
        block()
    } catch (se: SecurityException) {
        logger.warn("PermissionChecker", "security exception while checking $facet", se, "facet" to facet)
        false
    } catch (t: Throwable) {
        if (t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException) throw t
        logger.warn("PermissionChecker", "unexpected error while checking $facet", t, "facet" to facet)
        false
    }
}
