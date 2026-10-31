package me.shawnsquire.tandem.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.shawnsquire.tandem.ui.alarms.AlarmsScreen
import me.shawnsquire.tandem.ui.partner.PartnerScreen
import me.shawnsquire.tandem.ui.settings.SettingsScreen

private enum class Tab(val route: String, val label: String) {
    Alarms("alarms", "Alarms"),
    Partner("partner", "Partner"),
    Settings("settings", "Settings"),
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
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
                        // No icon set: material-icons-core/extended are not dependencies.
                        icon = {},
                        alwaysShowLabel = true,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Alarms.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Tab.Alarms.route) { AlarmsScreen() }
            composable(Tab.Partner.route) { PartnerScreen() }
            composable(Tab.Settings.route) { SettingsScreen() }
        }
    }
}
