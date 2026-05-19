package com.flowdroid.ui.flows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowdroid.common.flow.InstalledPackage
import com.flowdroid.common.flow.InstalledPackagesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives the [PackagePickerSheet]. Loads the installed-packages snapshot once and exposes a
 * lazily-filtered view that the picker UI binds against.
 *
 * Sorting rule: launchable apps with a non-blank label come first (alphabetical by label);
 * everything else comes after (alphabetical by package name). This matches the picker's job
 * of getting "the app the user actually wants" to the top.
 */
@HiltViewModel
class PackagePickerViewModel @Inject constructor(
    private val repository: InstalledPackagesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PackagePickerUiState>(PackagePickerUiState.Loading)
    val state: StateFlow<PackagePickerUiState> = _state.asStateFlow()

    private var query: String = ""
    private var allPackages: List<InstalledPackage> = emptyList()

    init { reload() }

    /** Force-refresh the snapshot — call when the user pulls-to-refresh. */
    fun reload() {
        viewModelScope.launch {
            _state.value = PackagePickerUiState.Loading
            try {
                allPackages = repository.snapshot().sortedWith(displayOrder)
                _state.value = PackagePickerUiState.Ready(
                    items = filter(allPackages, query),
                    query = query,
                )
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) throw t
                Timber.w(t, "PackagePicker: snapshot failed")
                _state.value = PackagePickerUiState.Ready(items = emptyList(), query = query)
            }
        }
    }

    /** Update the search query and re-filter against the cached snapshot. */
    fun setQuery(q: String) {
        query = q
        _state.update {
            when (it) {
                is PackagePickerUiState.Loading -> it
                is PackagePickerUiState.Ready -> it.copy(
                    items = filter(allPackages, q),
                    query = q,
                )
            }
        }
    }

    private fun filter(items: List<InstalledPackage>, q: String): List<InstalledPackage> {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return items
        val needle = trimmed.lowercase()
        return items.filter { pkg ->
            pkg.packageName.lowercase().contains(needle) ||
                (pkg.label?.lowercase()?.contains(needle) == true)
        }
    }

    companion object {
        /** Launchable + label-resolved first (by label); rest after (by package name). */
        internal val displayOrder: Comparator<InstalledPackage> = Comparator { a, b ->
            val aFirst = a.isLaunchable && !a.label.isNullOrBlank()
            val bFirst = b.isLaunchable && !b.label.isNullOrBlank()
            when {
                aFirst && !bFirst -> -1
                !aFirst && bFirst -> 1
                aFirst -> (a.label ?: "").compareTo(b.label ?: "", ignoreCase = true)
                else -> a.packageName.compareTo(b.packageName, ignoreCase = true)
            }
        }
    }
}

sealed interface PackagePickerUiState {
    data object Loading : PackagePickerUiState
    data class Ready(
        val items: List<InstalledPackage>,
        val query: String,
    ) : PackagePickerUiState
}
