package com.flowdroid.data.repo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.flowdroid.common.Clock
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.flow.InstalledPackage
import com.flowdroid.common.flow.InstalledPackagesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PackageManager]-backed implementation of [InstalledPackagesRepository].
 *
 * Caching:
 *  - The list of installed apps is fetched once on first access and cached. A
 *    [BroadcastReceiver] for `PACKAGE_ADDED` / `PACKAGE_REMOVED` / `PACKAGE_REPLACED` invalidates
 *    the cache and emits a fresh list.
 *  - Reading installed packages allocates a sizeable Bundle per app; fetching is on
 *    [Dispatchers.IO].
 *  - Icons are NOT pre-loaded here. UI loads them lazily via `PackageManager.getApplicationIcon`
 *    so the picker scrolls smoothly.
 *
 * `QUERY_ALL_PACKAGES` is declared in the manifest so this works on Android 11+.
 */
@Singleton
class InstalledPackagesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: StructuredLogger,
    @Suppress("unused") private val clock: Clock,
) : InstalledPackagesRepository {

    private val state: MutableStateFlow<List<InstalledPackage>> = MutableStateFlow(emptyList())
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var receiverRegistered = false

    override fun observe(): Flow<List<InstalledPackage>> {
        ensureReceiver()
        if (state.value.isEmpty()) {
            // Lazy load on first subscription.
            refreshScope.launch { refresh() }
        }
        return state.asStateFlow()
    }

    override suspend fun snapshot(): List<InstalledPackage> {
        ensureReceiver()
        if (state.value.isEmpty()) refresh()
        return state.value
    }

    override suspend fun refresh() {
        val now = withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val flags = PackageManager.GET_META_DATA.toLong()
                val apps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(flags.toInt())
                }
                apps.map { ai ->
                    val label = try {
                        pm.getApplicationLabel(ai)?.toString()
                    } catch (t: Throwable) {
                        if (t is OutOfMemoryError) throw t
                        null
                    }
                    val launchable = pm.getLaunchIntentForPackage(ai.packageName) != null
                    InstalledPackage(
                        packageName = ai.packageName,
                        label = label,
                        isLaunchable = launchable,
                    )
                }.sortedWith(
                    compareByDescending<InstalledPackage> { it.isLaunchable }
                        .thenBy { it.label?.lowercase() ?: it.packageName },
                )
            } catch (t: Throwable) {
                if (t is OutOfMemoryError ||
                    t is kotlin.coroutines.cancellation.CancellationException
                ) throw t
                logger.warn(TAG, "package list fetch failed", t)
                emptyList()
            }
        }
        state.value = now
        logger.info(TAG, "package list refreshed", "count" to now.size)
    }

    @Synchronized
    private fun ensureReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    refreshScope.launch { refresh() }
                }
            }
            // RECEIVER_NOT_EXPORTED — system broadcast; we don't need to be exported.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
            logger.warn(TAG, "failed to register package receiver", t)
            // Cache will be stale but lookups still return the last snapshot.
        }
    }

    companion object {
        private const val TAG = "PkgRepo"
    }
}
