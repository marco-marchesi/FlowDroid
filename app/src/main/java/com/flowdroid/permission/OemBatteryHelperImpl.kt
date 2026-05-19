package com.flowdroid.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.permission.DeepLinkTarget
import com.flowdroid.common.permission.OemBatteryHelper
import com.flowdroid.common.permission.OemBrand
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OEM-specific battery / background settings deep links.
 *
 * ---------------------------------------------------------------------------------------------
 * KNOWN OEM ACTIVITY PATHS (READ THIS BEFORE EDITING)
 * ---------------------------------------------------------------------------------------------
 * These activities are *not* part of the Android SDK. They are reverse-engineered from
 * shipped firmware and are widely published by automation/dontkillmyapp-style projects
 * (see https://dontkillmyapp.com/, VoiceNotify source, MacroDroid bug-tracker threads,
 * Stack Overflow Q&A).
 *
 * They are best-effort and WILL break eventually on:
 *   - new One UI / MIUI / ColorOS / OriginOS major versions
 *   - region-specific firmware (especially China builds vs. Global)
 *   - vendor-renamed packages after rebrands (e.g. Honor split from Huawei)
 *
 * Per-brand activities used here:
 *
 *   SAMSUNG  "Never sleeping apps" deep link (One UI 6.x as of writing):
 *     ComponentName("com.samsung.android.lool",
 *                   "com.samsung.android.sm.battery.ui.BatteryActivity")
 *     source: https://dontkillmyapp.com/samsung — also referenced by VoiceNotify and
 *             several Tasker community threads. May break in future One UI versions;
 *             Samsung has moved this UI between "lool", "smartmanager", and
 *             "DeviceCare" packages historically.
 *
 *   XIAOMI / Redmi / POCO (MIUI) autostart manager:
 *     ComponentName("com.miui.securitycenter",
 *                   "com.miui.permcenter.autostart.AutoStartManagementActivity")
 *
 *   OPPO / Realme (ColorOS / RealmeUI) startup manager:
 *     ComponentName("com.coloros.safecenter",
 *                   "com.coloros.safecenter.permission.startup.StartupAppListActivity")
 *     fallback for older ColorOS:
 *     ComponentName("com.oppo.safe",
 *                   "com.oppo.safe.permission.startup.StartupAppListActivity")
 *
 *   VIVO (FuntouchOS / OriginOS) whitelist:
 *     ComponentName("com.iqoo.secure",
 *                   "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
 *
 * Whenever we can't resolve the chosen activity we fall back to APP_INFO, which is
 * universally available on every Android device. The UI layer is responsible for
 * showing brand-specific *instructions* in case the deep link drops the user on the
 * generic app-info page instead of the precise screen.
 * ---------------------------------------------------------------------------------------------
 */
@Singleton
class OemBatteryHelperImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StructuredLogger,
) : OemBatteryHelper {

    private val packageName: String get() = context.packageName

    /**
     * Classifies the device by [Build.MANUFACTURER] (lowercased, trimmed).
     *
     * Mapping is deliberately conservative — we map known aliases (Redmi/POCO → Xiaomi,
     * Honor → Huawei). Everything else is [OemBrand.GENERIC] which means: use AOSP
     * intents only, no vendor-specific deep links.
     */
    override fun detect(): OemBrand {
        val mf = (Build.MANUFACTURER ?: "").trim().lowercase()
        return when (mf) {
            "samsung" -> OemBrand.SAMSUNG
            "xiaomi", "redmi", "poco" -> OemBrand.XIAOMI
            "oppo" -> OemBrand.OPPO
            "realme" -> OemBrand.REALME
            "oneplus" -> OemBrand.ONEPLUS
            "huawei", "honor" -> OemBrand.HUAWEI
            "vivo" -> OemBrand.VIVO
            else -> OemBrand.GENERIC
        }
    }

    /**
     * Returns the [Intent] to deep-link the user to the requested settings page, or
     * `null` if the target has no Settings representation on this device (e.g.
     * [DeepLinkTarget.POST_NOTIFICATIONS_RUNTIME] — that one is a runtime permission
     * request, not a Settings page).
     *
     * Edge cases:
     *  - If the OEM-specific activity name can't be resolved by [PackageManager],
     *    we fall back to the AOSP equivalent or APP_INFO.
     *  - All returned intents have [Intent.FLAG_ACTIVITY_NEW_TASK] set so they can be
     *    launched from a non-Activity context (e.g. a Service or BroadcastReceiver).
     *  - Any [SecurityException] thrown by the system is caught, logged WARN, and we
     *    return `null` to let the caller render a manual-instructions fallback.
     */
    override fun resolveDeepLink(target: DeepLinkTarget): Intent? {
        val brand = detect()
        return try {
            val intent = when (target) {
                DeepLinkTarget.NOTIFICATION_LISTENER_SETTINGS -> notificationListenerSettings()
                DeepLinkTarget.ACCESSIBILITY_SETTINGS -> accessibilitySettings()
                DeepLinkTarget.BATTERY_OPTIMISATION -> batteryOptimisation()
                DeepLinkTarget.APP_INFO -> appInfo()
                DeepLinkTarget.OEM_NEVER_SLEEPING_APPS -> oemNeverSleeping(brand)
                DeepLinkTarget.OEM_AUTOSTART -> oemAutostart(brand)
                DeepLinkTarget.EXACT_ALARM_PERMISSION -> exactAlarm()
                DeepLinkTarget.POST_NOTIFICATIONS_RUNTIME -> null
            }
            val resolved = intent != null && (intent.component == null || resolves(intent))
            logger.info(
                "OemBatteryHelper",
                "resolveDeepLink",
                "brand" to brand,
                "target" to target,
                "componentResolved" to resolved,
            )
            if (intent != null && intent.component != null && !resolves(intent)) {
                // OEM-specific activity not present — return AOSP fallback.
                appInfo()
            } else {
                intent
            }
        } catch (se: SecurityException) {
            logger.warn(
                "OemBatteryHelper",
                "resolveDeepLink security exception",
                se,
                "brand" to brand,
                "target" to target,
                "componentResolved" to false,
            )
            null
        }
    }

    // ----------------------------------------------------------------------------------------- AOSP

    /** Settings → "Notification access" / "Device & app notifications → Notification access". */
    private fun notificationListenerSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).withNewTask()

    /** Settings → Accessibility. */
    private fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).withNewTask()

    /**
     * Direct REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog (with `package:` URI). Falls back
     * to the list-style settings page if the request activity is missing — vanishingly
     * rare but defensive.
     */
    private fun batteryOptimisation(): Intent {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (resolves(direct)) {
            direct
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).withNewTask()
        }
    }

    /** Settings → App info (always available). */
    private fun appInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * SCHEDULE_EXACT_ALARM dialog on API 31+. Older APIs grant the permission at install
     * time, so there's no Settings page — we return `null` so callers can skip the step.
     */
    private fun exactAlarm(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val direct = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (resolves(direct)) direct else appInfo()
    }

    // ----------------------------------------------------------------------------------------- OEM

    /**
     * Samsung "Never sleeping apps" path. Documented at https://dontkillmyapp.com/samsung
     * and corroborated by the VoiceNotify open-source project. This path is FRAGILE — Samsung
     * has historically moved it between the `com.samsung.android.lool`,
     * `com.samsung.android.sm` and `com.samsung.android.smartmanager` packages.
     */
    private fun oemNeverSleeping(brand: OemBrand): Intent? = when (brand) {
        OemBrand.SAMSUNG -> Intent().apply {
            component = ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        else -> appInfo()
    }

    /**
     * OEM autostart manager. Xiaomi / Oppo / Realme / Vivo expose different activities;
     * see the file header for source attribution.
     */
    private fun oemAutostart(brand: OemBrand): Intent? = when (brand) {
        OemBrand.XIAOMI -> Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        OemBrand.OPPO, OemBrand.REALME -> Intent().apply {
            component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        OemBrand.VIVO -> Intent().apply {
            component = ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        else -> appInfo()
    }

    // --------------------------------------------------------------------------------------- utils

    private fun Intent.withNewTask(): Intent = apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /**
     * True if the given intent resolves to at least one activity on the current device.
     * Wrapped in try/catch — older Samsung firmware has been seen throwing here.
     */
    private fun resolves(intent: Intent): Boolean = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            ) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0) != null
        }
    } catch (se: SecurityException) {
        logger.warn(
            "OemBatteryHelper",
            "resolveActivity security exception",
            se,
            "intent" to intent.toString(),
        )
        false
    }
}
