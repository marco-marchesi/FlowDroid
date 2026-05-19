package com.flowdroid.common.flow

import kotlinx.coroutines.flow.Flow as KFlow

/**
 * Lists installed packages on the device. Backed by `PackageManager.getInstalledApplications`.
 *
 * Phase 2 introduces this for the trigger / `KillApp` editor's package picker — far more usable
 * than typing a raw package name.
 *
 * Implementations MUST be cache-aware: `getInstalledApplications` allocates ~MB of bitmaps for
 * 200+ apps, so we cache the snapshot and invalidate only on PACKAGE_ADDED/REMOVED broadcasts.
 */
interface InstalledPackagesRepository {

    /** Cold flow that emits the current list whenever the cache is invalidated. */
    fun observe(): KFlow<List<InstalledPackage>>

    /**
     * One-shot snapshot. Useful for the picker's "load on demand" rather than subscribing.
     * Implementations MAY return a cached list if recent; the cache TTL is implementation-defined.
     */
    suspend fun snapshot(): List<InstalledPackage>

    /** Force-refresh the cache (e.g. after the user installs an app while the picker is open). */
    suspend fun refresh()
}

/**
 * @property packageName       e.g. `com.example.app`. Stable identifier.
 * @property label             User-visible app name. May be null if the system can't resolve it.
 * @property isLaunchable      True if [packageName] has a launcher activity — the picker may
 *                             choose to hide non-launchable system internals by default.
 * @property iconPath          Optional path to the icon. Absent for v0; the UI can fall back to
 *                             querying `PackageManager.getApplicationIcon(packageName)` itself.
 */
data class InstalledPackage(
    val packageName: String,
    val label: String?,
    val isLaunchable: Boolean,
    val iconPath: String? = null,
)
