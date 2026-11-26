package com.aitts.v2.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aitts.v2.di.AppContainer
import com.aitts.v2.ui.hub.SpeechHubScreen
import com.aitts.v2.ui.hub.SpeechHubViewModel
import com.aitts.v2.ui.providers.ProviderEditScreen
import com.aitts.v2.ui.providers.ProvidersScreen
import com.aitts.v2.ui.providers.ProvidersViewModel
import com.aitts.v2.ui.rules.RulesScreen
import com.aitts.v2.ui.rules.RulesViewModel
import com.aitts.v2.ui.settings.SettingsScreen
import com.aitts.v2.ui.settings.SettingsViewModel

private sealed class TabRoute(val route: String, val label: String, val icon: ImageVector) {
    data object Hub : TabRoute("hub", "朗读", Icons.Default.GraphicEq)
    data object Providers : TabRoute("providers", "模型", Icons.Default.RecordVoiceOver)
    data object Rules : TabRoute("rules", "规则", Icons.Default.Spellcheck)
    data object Settings : TabRoute("settings", "设置", Icons.Default.Tune)
}

@Composable
fun AiTtsAppRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val tabs = listOf(TabRoute.Hub, TabRoute.Providers, TabRoute.Rules, TabRoute.Settings)

    val hubVm: SpeechHubViewModel = viewModel(factory = SpeechHubViewModel.Factory(container))
    val providersVm: ProvidersViewModel = viewModel(factory = ProvidersViewModel.Factory(container))
    val rulesVm: RulesViewModel = viewModel(factory = RulesViewModel.Factory(container))
    val settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container))

    Scaffold(
        bottomBar = {
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TabRoute.Hub.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TabRoute.Hub.route) {
                SpeechHubScreen(
                    viewModel = hubVm,
                    onOpenProviders = { navController.navigate(TabRoute.Providers.route) },
                    onEditActiveProvider = { id -> navController.navigate("provider_edit/$id") }
                )
            }
            composable(TabRoute.Providers.route) {
                ProvidersScreen(
                    viewModel = providersVm,
                    onEditProvider = { id -> navController.navigate("provider_edit/$id") }
                )
            }
            composable(
                route = "provider_edit/{providerId}",
                arguments = listOf(navArgument("providerId") { type = NavType.StringType })
            ) { entry ->
                val providerId = entry.arguments?.getString("providerId") ?: "new"
                ProviderEditScreen(
                    providerId = providerId,
                    viewModel = providersVm,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(TabRoute.Rules.route) {
                RulesScreen(viewModel = rulesVm)
            }
            composable(TabRoute.Settings.route) {
                SettingsScreen(viewModel = settingsVm)
            }
        }
    }
}
