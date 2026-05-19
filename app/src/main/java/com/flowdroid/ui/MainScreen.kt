package com.flowdroid.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.flowdroid.ui.flows.FlowEditorRoute
import com.flowdroid.ui.flows.FlowsListRoute
import com.flowdroid.ui.health.HealthRoute
import com.flowdroid.ui.log.LogRoute
import com.flowdroid.ui.setup.SetupRoute

/**
 * Top-level container for the FlowDroid Phase 0 app. Three tabs:
 *
 *  - [TopLevelDestination.Health] — live service/listener/accessibility status.
 *  - [TopLevelDestination.Logs]   — notification + structured log viewer.
 *  - [TopLevelDestination.Setup]  — permission wizard.
 *
 * State is purely navigation; everything else lives in screen-level ViewModels.
 */
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.testTag("main_scaffold"),
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("main_nav_bar")) {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav_${destination.route}"),
                        selected = selected,
                        onClick = {
                            if (currentRoute != destination.route) {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Flows.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevelDestination.Flows.route) {
                FlowsListRoute(navController = navController)
            }
            composable(
                route = "flows/{flowId}",
                arguments = listOf(navArgument("flowId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }),
            ) { entry ->
                val raw = entry.arguments?.getString("flowId")
                val flowId = if (raw.isNullOrBlank() || raw == "new") null else raw
                FlowEditorRoute(flowId = flowId, navController = navController)
            }
            composable(
                route = "flows/{flowId}/runs",
                arguments = listOf(navArgument("flowId") { type = NavType.StringType }),
            ) {
                com.flowdroid.ui.flows.FlowRunsRoute(navController = navController)
            }
            composable(TopLevelDestination.Health.route) { HealthRoute() }
            composable(TopLevelDestination.Logs.route) { LogRoute() }
            composable(TopLevelDestination.Setup.route) { SetupRoute() }
        }
    }
}

/** Stable enum so the nav bar can be data-driven and the tests can iterate over entries. */
enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    Flows("flows", "Flows", Icons.Filled.AccountTree),
    Health("health", "Health", Icons.Filled.MonitorHeart),
    Logs("logs", "Logs", Icons.Filled.ReceiptLong),
    Setup("setup", "Setup", Icons.Filled.SettingsSuggest),
}
