package com.flowdroid.ui.flows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.flowdroid.common.domain.FlowRun
import com.flowdroid.common.domain.FlowRunActionResult
import com.flowdroid.ui.formatTimeOfDay
import com.flowdroid.ui.theme.StatusGreen
import com.flowdroid.ui.theme.StatusRed
import com.flowdroid.ui.theme.bodyMonospace

/**
 * Read-only execution-history screen for a single flow. Reachable from the Flows list's
 * long-press menu via "View runs". Backed by [FlowRunsViewModel] / [FlowRunRepository].
 *
 * The list is newest-first; each row is a card showing trigger kind, time, duration and a
 * green/red status badge. Tap to expand a per-action timeline (rows from the engine's
 * [com.flowdroid.engine.RunRecorder]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowRunsRoute(
    navController: NavController,
    viewModel: FlowRunsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("flow_runs_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Runs") },
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.testTag("flow_runs_back"),
                        onClick = { navController.popBackStack() },
                    ) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                FlowRunsUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize().testTag("flow_runs_loading"),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                FlowRunsUiState.Empty -> EmptyState()
                is FlowRunsUiState.Error -> Box(
                    modifier = Modifier.fillMaxSize().testTag("flow_runs_error").padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Couldn't load runs: ${s.message}", color = MaterialTheme.colorScheme.error) }
                is FlowRunsUiState.Ready -> RunsList(s.runs)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("flow_runs_empty"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No runs yet.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Once this flow fires, every execution shows up here with per-action timing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunsList(runs: List<FlowRun>) {
    val expanded = remember { mutableStateMapOf<Long, Unit>() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("flow_runs_list"),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(runs, key = { "${it.id}_${it.executionId}" }) { run ->
            val key = (run.id.toString() + run.executionId).hashCode().toLong()
            RunCard(
                run = run,
                isExpanded = expanded.containsKey(key),
                onClick = {
                    if (expanded.containsKey(key)) expanded.remove(key) else expanded[key] = Unit
                },
            )
        }
    }
}

@Composable
private fun RunCard(run: FlowRun, isExpanded: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("flow_run_row_${run.executionId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(ok = run.ok)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${run.triggerKind} · ${formatTimeOfDay(run.startedAtMillis)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "ok=${run.okCount} err=${run.errCount} · ${run.durationMillis}ms · exec ${run.executionId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (run.ok) "PASS" else if (run.aborted) "ABORT" else "ERR",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (run.ok) StatusGreen else StatusRed,
                    fontWeight = FontWeight.Bold,
                )
            }
            run.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "✗ $it",
                    style = bodyMonospace,
                    color = StatusRed,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                )
            }
            AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    if (run.actionResults.isEmpty()) {
                        Text(
                            "(no per-action data — flow had no actions)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        run.actionResults.forEach { result -> ActionResultRow(result) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionResultRow(result: FlowRunActionResult) {
    val color = when (result.status) {
        "ok" -> StatusGreen
        else -> StatusRed
    }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row {
            Text("${result.index + 1}.", style = bodyMonospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text(result.actionClass, style = bodyMonospace, fontWeight = FontWeight.SemiBold)
            result.label?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(8.dp))
                Text("· $it", style = bodyMonospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text(result.status.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(6.dp))
            Text("${result.durationMs}ms", style = bodyMonospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        result.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = bodyMonospace, color = color, modifier = Modifier.padding(start = 18.dp))
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (ok) StatusGreen else StatusRed,
                shape = CircleShape,
            ),
    )
}
