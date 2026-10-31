package me.shawnsquire.tandem.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.shawnsquire.tandem.ui.alarms.AlarmsRoute
import me.shawnsquire.tandem.ui.alarms.edit.AlarmEditRoute
import me.shawnsquire.tandem.ui.partner.PartnerRoute
import me.shawnsquire.tandem.ui.settings.SettingsRoute
import me.shawnsquire.tandem.ui.settings.health.HealthRoute

private const val DELETED_ALARM_KEY = "deletedAlarmId"
private const val EDIT_ROUTE = "alarm/edit"
private const val HEALTH_ROUTE = "settings/health"

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Alarms("alarms", "Alarms", Icons.Default.Notifications),
    Partner("partner", "Partner", Icons.Default.Person),
    Settings("settings", "Settings", Icons.Default.Settings),
}

/**
 * Runs [action] only while this entry is the settled, visible one. A second tap during the exit or
 * enter transition arrives with the entry below RESUMED and is dropped, so navigation never stacks
 * a second editor or pops the start destination.
 */
private fun NavBackStackEntry.ifResumed(action: () -> Unit) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action()
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val isTabRoute = Tab.entries.any { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }

    Scaffold(
        bottomBar = {
            if (isTabRoute) NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Alarms.route,
            // Tabs have no top bar of their own, so they take the system-bar and bottom-bar padding.
            // The full-screen routes (editor, health) own a Scaffold and apply their insets themselves.
            modifier = if (isTabRoute) Modifier.padding(innerPadding).consumeWindowInsets(innerPadding) else Modifier,
        ) {
            composable(Tab.Alarms.route) { entry ->
                val deletedId by entry.savedStateHandle
                    .getStateFlow<String?>(DELETED_ALARM_KEY, null)
                    .collectAsStateWithLifecycle()
                AlarmsRoute(
                    onAddAlarm = { entry.ifResumed { navController.navigate(EDIT_ROUTE) } },
                    onEditAlarm = { id -> entry.ifResumed { navController.navigate("alarm/edit?id=$id") } },
                    deletedAlarmId = deletedId,
                    onDeletedConsumed = { entry.savedStateHandle[DELETED_ALARM_KEY] = null },
                    onOpenHealth = { navController.navigate(HEALTH_ROUTE) },
                )
            }
            composable(
                route = "alarm/edit?id={$ALARM_ID_ARG}",
                arguments = listOf(
                    navArgument(ALARM_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                AlarmEditRoute(
                    // Close is driven by state (also after a save finishing in the background, when the entry
                    // is not RESUMED), so it only refuses once the entry is already leaving.
                    onClose = {
                        if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) navController.popBackStack()
                    },
                    onDelete = { id ->
                        entry.ifResumed {
                            navController.previousBackStackEntry?.savedStateHandle?.set(DELETED_ALARM_KEY, id)
                            navController.popBackStack()
                        }
                    },
                )
            }
            composable(Tab.Partner.route) { PartnerRoute() }
            composable(Tab.Settings.route) { SettingsRoute(onOpenHealth = { navController.navigate(HEALTH_ROUTE) }) }
            composable(HEALTH_ROUTE) { HealthRoute(onBack = { navController.popBackStack() }) }
        }
    }
}
