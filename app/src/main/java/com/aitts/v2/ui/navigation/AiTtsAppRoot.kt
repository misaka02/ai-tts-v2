package com.aitts.v2.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    data object Hub : TabRoute("hub", "听书台", Icons.Outlined.AutoStories)
    data object Providers : TabRoute("providers", "声库", Icons.Outlined.GraphicEq)
    data object Rules : TabRoute("rules", "词典", Icons.Outlined.Spellcheck)
    data object Settings : TabRoute("settings", "偏好", Icons.Outlined.Tune)
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

    LaunchedEffect(container) {
        container.incomingTextEvents.collect { sharedText ->
            navController.navigate(TabRoute.Hub.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            hubVm.speakExternalText(sharedText)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentRoute in tabs.map { it.route }) {
                EditorialInkBottomDock(
                    tabs = tabs,
                    currentRoute = currentRoute,
                    onSelect = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
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

@Composable
private fun EditorialInkBottomDock(
    tabs: List<TabRoute>,
    currentRoute: String?,
    onSelect: (TabRoute) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    val tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(tab) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(2.dp)
                                .background(
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(1.dp)
                                )
                        )
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}
