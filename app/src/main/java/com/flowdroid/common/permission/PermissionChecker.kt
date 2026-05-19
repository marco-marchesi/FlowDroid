package com.flowdroid.common.permission

import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for permission status. Implementations poll the system or react to
 * Settings round-trips and expose a [Flow] so UI can react live.
 */
interface PermissionChecker {

    fun observe(): Flow<PermissionSnapshot>

    fun snapshot(): PermissionSnapshot

    /** Manual refresh — call when returning from a Settings activity. */
    fun refresh()
}

data class PermissionSnapshot(
    val notificationListenerEnabled: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val postNotificationsGranted: Boolean,
    val batteryOptimisationIgnored: Boolean,
    val exactAlarmAllowed: Boolean,
    val overlayPermissionGranted: Boolean,
    val oemSpecific: OemSpecific = OemSpecific.None,
) {
    val allRequiredGranted: Boolean
        get() = notificationListenerEnabled &&
                postNotificationsGranted &&
                batteryOptimisationIgnored
}

sealed interface OemSpecific {
    data object None : OemSpecific
    data class Samsung(val deviceCareConfigured: Boolean, val backgroundUsageNotLimited: Boolean) : OemSpecific
    data class Xiaomi(val autostartConfigured: Boolean, val batterySaverConfigured: Boolean) : OemSpecific
    data class Oppo(val autostartConfigured: Boolean, val backgroundFreezeDisabled: Boolean) : OemSpecific
    data class Realme(val autostartConfigured: Boolean) : OemSpecific
    data class OnePlus(val deepOptimisationDisabled: Boolean) : OemSpecific
}

/**
 * Helper that returns the Android Intent for the deep link to the relevant Settings page.
 * Returns null if no deep link exists on the current device.
 */
interface OemBatteryHelper {
    fun detect(): OemBrand
    fun resolveDeepLink(target: DeepLinkTarget): android.content.Intent?
}

enum class OemBrand { GENERIC, SAMSUNG, XIAOMI, OPPO, REALME, ONEPLUS, HUAWEI, VIVO }

enum class DeepLinkTarget {
    NOTIFICATION_LISTENER_SETTINGS,
    ACCESSIBILITY_SETTINGS,
    BATTERY_OPTIMISATION,
    APP_INFO,
    OEM_NEVER_SLEEPING_APPS,
    OEM_AUTOSTART,
    EXACT_ALARM_PERMISSION,
    POST_NOTIFICATIONS_RUNTIME,
}
