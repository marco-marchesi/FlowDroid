package com.flowdroid.ui.flows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowdroid.common.flow.InstalledPackage

/**
 * Bottom-sheet picker that lets the user pick a package from the installed apps on the device.
 *
 * Visual structure:
 *   [Search bar]
 *   ── divider ──
 *   [LazyColumn of apps]
 *
 * The picker doesn't render real app icons (no coil-compose dependency by design). Instead it
 * shows a uniform Android-glyph placeholder per row — adequate for a first-pass picker and
 * orders-of-magnitude cheaper than `applicationInfo.loadIcon()` for 200+ apps on the cold path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagePickerSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: PackagePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        modifier = Modifier.testTag("package_picker_sheet"),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Pick an app",
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            val query = (state as? PackagePickerUiState.Ready)?.query.orEmpty()
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("package_picker_search"),
                value = query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Filter by name or package") },
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            when (val s = state) {
                is PackagePickerUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp)
                            .testTag("package_picker_loading"),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                is PackagePickerUiState.Ready -> {
                    if (s.items.isEmpty()) {
                        EmptyState(query = s.query)
                    } else {
                        PackageList(items = s.items, onPick = onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .padding(24.dp)
            .testTag("package_picker_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (query.isBlank()) "No apps found." else "No apps matching \"$query\".",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PackageList(
    items: List<InstalledPackage>,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("package_picker_list"),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(items = items, key = { it.packageName }) { pkg ->
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(pkg.packageName) }
                    .testTag("package_picker_item_${pkg.packageName}"),
                leadingContent = {
                    Icon(Icons.Filled.Android, contentDescription = null)
                },
                headlineContent = {
                    Text(pkg.label?.takeIf { it.isNotBlank() } ?: pkg.packageName)
                },
                supportingContent = {
                    Text(pkg.packageName, style = MaterialTheme.typography.bodySmall)
                },
                trailingContent = {
                    if (!pkg.isLaunchable) {
                        Text(
                            "system",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
