package com.thecryptosteer.xmrminer.ui

import com.thecryptosteer.xmrminer.BuildConfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination

private enum class AppRoute(val route: String, val title: String) {
    DASHBOARD("dashboard", "CryptoSteer Miner"),
    MINING("mining", "Mining"),
    POOLS("pools", "Pool profiles"),
    TUNING("tuning", "Tuning"),
    DEVICE("device", "Device"),
    CONTROL("control", "Control"),
    MORE("more", "More"),
    POWER_PROFILES("power-profiles", "Power profiles"),
    CUSTOM_TUNING("custom-tuning", "Custom tuning"),
    BENCHMARK("benchmark", "Benchmark"),
    STATISTICS("statistics", "Statistics"),
    DIAGNOSTICS("diagnostics", "Diagnostics"),
    LOGS("logs", "Logs"),
    SETTINGS("settings", "Application settings"),
    ABOUT("about", "About"),
    PRIVACY("privacy", "Privacy and data"),
    TROUBLESHOOTING("troubleshooting", "Troubleshooting"),
}

private data class RootNavigationItem(
    val route: AppRoute,
    val label: String,
    val icon: ImageVector,
)

private val rootNavigationItems = listOf(
    RootNavigationItem(AppRoute.DASHBOARD, "Dashboard", Icons.Outlined.Dashboard),
    RootNavigationItem(AppRoute.MINING, "Mining", Icons.Outlined.Memory),
    RootNavigationItem(AppRoute.POOLS, "Pools", Icons.Outlined.Public),
    RootNavigationItem(AppRoute.TUNING, "Tune", Icons.Outlined.Tune),
    RootNavigationItem(AppRoute.DEVICE, "Device", Icons.Outlined.DeveloperBoard),
    RootNavigationItem(AppRoute.CONTROL, "Control", Icons.Outlined.DevicesOther),
    RootNavigationItem(AppRoute.MORE, "More", Icons.Outlined.MoreHoriz),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoSteerApp(
    viewModel: MainViewModel,
    onEnableBiometric: () -> Unit,
    onUnlockBiometric: () -> Unit,
    onOpenInstallPermission: () -> Unit,
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    if (BuildConfig.SELF_UPDATE_ENABLED && updateState.checking) {
        UpdateCheckingScreen(updateState)
        return
    }
    if (BuildConfig.SELF_UPDATE_ENABLED && updateState.blocked) {
        UpdateRequiredScreen(
            state = updateState,
            canInstallPackages = viewModel.canInstallPackages(),
            onUpdate = viewModel::installUpdate,
            onOpenInstallPermission = onOpenInstallPermission,
            onRetry = viewModel::checkForUpdates,
        )
        return
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    if (!settings.disclosureAccepted) {
        DisclosureScreen(viewModel::acceptDisclosure)
        return
    }

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val benchmarkJson by viewModel.benchmarkJson.collectAsStateWithLifecycle()
    val autoTuneJson by viewModel.autoTuneJson.collectAsStateWithLifecycle()
    val autoTuneState by viewModel.autoTuneState.collectAsStateWithLifecycle()
    val benchmarkState by viewModel.benchmarkState.collectAsStateWithLifecycle()
    val diagnosticsState by viewModel.diagnosticsState.collectAsStateWithLifecycle()
    val deviceInfoState by viewModel.deviceInfoState.collectAsStateWithLifecycle()
    val controlState by viewModel.controlState.collectAsStateWithLifecycle()
    val donationTier by viewModel.donationTierState.collectAsStateWithLifecycle()
    val selectedRig = controlState.rigs.firstOrNull { it.deviceId == controlState.selectedRigId }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppRoute.DASHBOARD.route
    val currentAppRoute = AppRoute.entries.firstOrNull { it.route == currentRoute } ?: AppRoute.DASHBOARD
    val selectedRoot = rootForRoute(currentAppRoute)

    MonitoringBackdrop {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val navigationMode = when {
                maxWidth >= 1_080.dp -> NavigationMode.EXPANDED
                maxWidth >= 720.dp -> NavigationMode.RAIL
                else -> NavigationMode.BOTTOM
            }
            Row(Modifier.fillMaxSize()) {
            when (navigationMode) {
                NavigationMode.RAIL -> AppNavigationRail(selectedRoot, navController)
                NavigationMode.EXPANDED -> AppPersistentNavigation(selectedRoot, navController)
                NavigationMode.BOTTOM -> Unit
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        modifier = Modifier.monitoringPanel(),
                        title = {
                            Column {
                                Text(currentAppRoute.title)
                                val activeProfile = profiles.firstOrNull { it.defaultProfile && it.enabled } ?: profiles.firstOrNull { it.enabled }
                                if (currentAppRoute == AppRoute.DASHBOARD && activeProfile != null) {
                                    Text(
                                        if (selectedRig == null) activeProfile.name else "${selectedRig.displayName} • ${activeProfile.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (currentAppRoute !in rootNavigationItems.map { it.route }) {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                                }
                            }
                        },
                        actions = {
                            if (currentAppRoute == AppRoute.DASHBOARD || currentAppRoute == AppRoute.MINING) {
                                MiningStatusChip(snapshot.state, Modifier.padding(end = 12.dp))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = CryptoSteerThemeTokens.colors.navigation),
                    )
                },
                bottomBar = {
                    if (navigationMode == NavigationMode.BOTTOM) AppBottomNavigation(selectedRoot, navController)
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (BuildConfig.SELF_UPDATE_ENABLED && updateState.error.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    updateState.error,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = viewModel::checkForUpdates) { Text("Retry") }
                            }
                        }
                    }
                    if (selectedRig != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Remote target: ${selectedRig.displayName}", style = MaterialTheme.typography.titleSmall)
                                    Text(if (selectedRig.online) "Live remote state" else "Offline — last known state", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { viewModel.selectRig(null) }) { Text("This device") }
                            }
                        }
                    }
                    NavHost(
                        navController = navController,
                        startDestination = AppRoute.DASHBOARD.route,
                        modifier = Modifier.fillMaxSize().weight(1f),
                    ) {
                    composable(AppRoute.DASHBOARD.route) {
                        DashboardScreen(
                            snapshot = snapshot,
                            profiles = profiles,
                            history = history,
                            logs = logs,
                            onStart = viewModel::start,
                            onPause = viewModel::pause,
                            onResume = viewModel::resume,
                            onStop = viewModel::stop,
                            onOpenProfiles = { navController.navigate(AppRoute.POOLS.route) },
                            onOpenMining = { navController.navigate(AppRoute.MINING.route) },
                        )
                    }
                    composable(AppRoute.MINING.route) {
                        MiningDetailsScreen(snapshot, logs) { navController.navigate(AppRoute.LOGS.route) }
                    }
                    composable(AppRoute.POOLS.route) { PoolProfilesScreen(profiles, viewModel) }
                    composable(AppRoute.TUNING.route) {
                        TuningHubScreen(
                            settings = settings,
                            snapshot = snapshot,
                            onOpenProfiles = { navController.navigate(AppRoute.POWER_PROFILES.route) },
                            onOpenCustom = { navController.navigate(AppRoute.CUSTOM_TUNING.route) },
                        )
                    }
                    composable(AppRoute.POWER_PROFILES.route) {
                        PowerProfilesScreen(settings, profiles, autoTuneState, autoTuneJson, viewModel)
                    }
                    composable(AppRoute.CUSTOM_TUNING.route) { CustomTuningScreen(settings, snapshot, viewModel) }
                    composable(AppRoute.DEVICE.route) {
                        DeviceScreen(
                            state = deviceInfoState,
                            miningSnapshot = snapshot,
                            settings = settings,
                            onRefresh = viewModel::refreshDeviceInfo,
                        )
                    }
                    composable(AppRoute.CONTROL.route) {
                        ControlScreen(
                            state = controlState,
                            onRegister = viewModel::registerAccount,
                            onLogin = viewModel::loginAccount,
                            onRecover = viewModel::recoverAccount,
                            onLogout = viewModel::logoutAccount,
                            onSelectRig = viewModel::selectRig,
                            onApproveDevice = viewModel::approveDevice,
                            onRefresh = viewModel::refreshRigs,
                            onEnableBiometric = onEnableBiometric,
                            onSkipBiometric = viewModel::skipBiometric,
                            onUnlockBiometric = onUnlockBiometric,
                        )
                    }
                    composable(AppRoute.MORE.route) {
                        MoreScreen(
                            onBenchmark = { navController.navigate(AppRoute.BENCHMARK.route) },
                            onStatistics = { navController.navigate(AppRoute.STATISTICS.route) },
                            onDiagnostics = { navController.navigate(AppRoute.DIAGNOSTICS.route) },
                            onLogs = { navController.navigate(AppRoute.LOGS.route) },
                            onSettings = { navController.navigate(AppRoute.SETTINGS.route) },
                            onAbout = { navController.navigate(AppRoute.ABOUT.route) },
                            onPrivacy = { navController.navigate(AppRoute.PRIVACY.route) },
                            onTroubleshooting = { navController.navigate(AppRoute.TROUBLESHOOTING.route) },
                        )
                    }
                    composable(AppRoute.BENCHMARK.route) { BenchmarkScreen(benchmarkJson, benchmarkState, snapshot, viewModel) }
                    composable(AppRoute.STATISTICS.route) { StatisticsScreen(history, snapshot) }
                    composable(AppRoute.DIAGNOSTICS.route) { DiagnosticsScreen(snapshot, diagnosticsState, viewModel) }
                    composable(AppRoute.LOGS.route) { LogsScreen(logs, viewModel::clearLogs) }
                    composable(AppRoute.SETTINGS.route) { SettingsScreen(snapshot) }
                    composable(AppRoute.ABOUT.route) { AboutScreen(snapshot, donationTier) }
                    composable(AppRoute.PRIVACY.route) { PrivacyScreen() }
                    composable(AppRoute.TROUBLESHOOTING.route) { TroubleshootingScreen() }
                    }
                }
            }
        }
        }
    }
}

private enum class NavigationMode { BOTTOM, RAIL, EXPANDED }

@Composable
private fun AppBottomNavigation(selected: AppRoute, navController: NavController) {
    NavigationBar(containerColor = CryptoSteerThemeTokens.colors.navigation, tonalElevation = 0.dp) {
        rootNavigationItems.forEach { item ->
            NavigationBarItem(
                selected = item.route == selected,
                onClick = { navController.navigateRoot(item.route) },
                icon = { Icon(item.icon, item.label) },
                label = { Text(item.label) },
                alwaysShowLabel = false,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = CryptoSteerThemeTokens.colors.selected,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun AppNavigationRail(selected: AppRoute, navController: NavController) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = CryptoSteerThemeTokens.colors.navigation,
        header = {
            Icon(Icons.AutoMirrored.Outlined.List, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 18.dp))
        },
    ) {
        rootNavigationItems.forEach { item ->
            NavigationRailItem(
                selected = item.route == selected,
                onClick = { navController.navigateRoot(item.route) },
                icon = { Icon(item.icon, item.label) },
                label = { Text(item.label) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = CryptoSteerThemeTokens.colors.selected,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun AppPersistentNavigation(selected: AppRoute, navController: NavController) {
    Surface(
        modifier = Modifier.width(236.dp).fillMaxHeight().monitoringPanel(MaterialTheme.colorScheme.primary),
        color = CryptoSteerThemeTokens.colors.navigation,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CryptoSteer", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            Text("Performance console", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            rootNavigationItems.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label) },
                    selected = item.route == selected,
                    onClick = { navController.navigateRoot(item.route) },
                    icon = { Icon(item.icon, null) },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = CryptoSteerThemeTokens.colors.selected,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
    }
}

private fun NavController.navigateRoot(route: AppRoute) {
    navigate(route.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun rootForRoute(route: AppRoute): AppRoute = when (route) {
    AppRoute.DASHBOARD -> AppRoute.DASHBOARD
    AppRoute.MINING -> AppRoute.MINING
    AppRoute.POOLS -> AppRoute.POOLS
    AppRoute.TUNING, AppRoute.POWER_PROFILES, AppRoute.CUSTOM_TUNING -> AppRoute.TUNING
    AppRoute.DEVICE -> AppRoute.DEVICE
    AppRoute.CONTROL -> AppRoute.CONTROL
    else -> AppRoute.MORE
}
