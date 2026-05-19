package com.flowdroid.permission

import com.flowdroid.common.permission.OemBatteryHelper
import com.flowdroid.common.permission.OemBrand
import com.flowdroid.common.permission.OemSpecific
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the [OemSpecific] facet of a [com.flowdroid.common.permission.PermissionSnapshot].
 *
 * **Honest caveat (read carefully):** none of the OEM-specific "is the app on the never-sleeping
 * list" / "is autostart enabled" states are programmatically queryable from a normal user app.
 * The vendor settings (Samsung Device Care, MIUI Autostart, ColorOS Startup Manager…) are not
 * exposed via [android.content.ContentResolver] or any public API. We have two options:
 *
 *  1. Pretend everything is fine and silently let the listener be killed in the background.
 *  2. Tell the UI "we *don't know* — please ask the user to confirm manually" by returning
 *     `false` for every per-OEM boolean here.
 *
 * This class picks option (2). The First-Run Wizard (§7.6) prompts the user with brand-specific
 * instructions and a "I've done it — mark complete" button which then writes back into a
 * separate DataStore-backed user-confirmation flag. That UX flag is NOT this class's concern;
 * we only report the *system-observable* state, which on every supported OEM is just "unknown
 * → treat as not configured".
 *
 * If a future Android version (or vendor) starts exposing these settings programmatically,
 * this is where to plug in the new check.
 */
@Singleton
class OemDetector @Inject constructor(
    private val helper: OemBatteryHelper,
) {

    /**
     * Returns the [OemSpecific] payload for the current device. The per-brand booleans are
     * always conservative defaults — see the class KDoc for why.
     */
    fun detect(): OemSpecific = when (helper.detect()) {
        OemBrand.SAMSUNG -> OemSpecific.Samsung(
            deviceCareConfigured = false,
            backgroundUsageNotLimited = false,
        )
        OemBrand.XIAOMI -> OemSpecific.Xiaomi(
            autostartConfigured = false,
            batterySaverConfigured = false,
        )
        OemBrand.OPPO -> OemSpecific.Oppo(
            autostartConfigured = false,
            backgroundFreezeDisabled = false,
        )
        OemBrand.REALME -> OemSpecific.Realme(autostartConfigured = false)
        OemBrand.ONEPLUS -> OemSpecific.OnePlus(deepOptimisationDisabled = false)
        // Huawei, Vivo, Generic: no dedicated OemSpecific variant — return None.
        OemBrand.HUAWEI, OemBrand.VIVO, OemBrand.GENERIC -> OemSpecific.None
    }
}
