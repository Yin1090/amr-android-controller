package com.yin1090.amrcontroller

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yin1090.amrcontroller.ui.screens.CockpitScreen
import com.yin1090.amrcontroller.ui.screens.ConnectionScreen
import com.yin1090.amrcontroller.ui.screens.PreflightScreen
import com.yin1090.amrcontroller.ui.state.CockpitViewModel
import com.yin1090.amrcontroller.ui.theme.AmrControllerTheme
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private object Routes {
    const val CONNECTION = "connection"
    const val PREFLIGHT = "preflight/{demoMode}?warning={warning}"
    const val COCKPIT = "cockpit"

    fun preflight(demoMode: Boolean, warning: String?): String {
        val encoded = URLEncoder.encode(warning.orEmpty(), StandardCharsets.UTF_8.name())
        return "preflight/$demoMode?warning=$encoded"
    }
}

@Composable
fun AmrApp(viewModel: CockpitViewModel = viewModel()) {
    AmrControllerTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route.orEmpty()

        val activity = LocalContext.current as? Activity
        val isCompactWidth = LocalConfiguration.current.smallestScreenWidthDp < 600

        LaunchedEffect(route, isCompactWidth) {
            activity?.requestedOrientation = when {
                route == Routes.COCKPIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                isCompactWidth -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        NavHost(
            navController = navController,
            startDestination = Routes.CONNECTION
        ) {
            composable(Routes.CONNECTION) {
                val formState by viewModel.connectionForm.collectAsState()
                ConnectionScreen(
                    formState = formState,
                    onHostChange = viewModel::updateHost,
                    onApiPortChange = viewModel::updateApiPort,
                    onCamPortChange = viewModel::updateCamPort,
                    onConnectClick = {
                        viewModel.connectLive { demoMode, warning ->
                            navController.navigate(Routes.preflight(demoMode, warning))
                        }
                    },
                    onOfflineDemoClick = {
                        viewModel.startOfflineDemo {
                            navController.navigate(Routes.preflight(demoMode = true, warning = null))
                        }
                    }
                )
            }

            composable(
                route = Routes.PREFLIGHT,
                arguments = listOf(
                    navArgument("demoMode") { type = NavType.BoolType },
                    navArgument("warning") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                val demoMode = entry.arguments?.getBoolean("demoMode") ?: false
                val rawWarning = entry.arguments?.getString("warning").orEmpty()
                val warning = URLDecoder.decode(rawWarning, StandardCharsets.UTF_8.name()).takeIf { it.isNotBlank() }
                val preflightState by viewModel.preflightState.collectAsState()

                LaunchedEffect(demoMode, warning) {
                    viewModel.runPreflight(demoMode, warning) {
                        navController.navigate(Routes.COCKPIT) {
                            popUpTo(Routes.CONNECTION) { inclusive = false }
                        }
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        viewModel.cancelPreflight()
                    }
                }

                PreflightScreen(
                    state = preflightState,
                    demoMode = demoMode,
                    onRetry = {
                        viewModel.runPreflight(demoMode, warning) {
                            navController.navigate(Routes.COCKPIT) {
                                popUpTo(Routes.CONNECTION) { inclusive = false }
                            }
                        }
                    },
                    onBack = {
                        viewModel.cancelPreflight()
                        navController.popBackStack()
                    },
                    onDemoCockpit = {
                        viewModel.cancelPreflight()
                        viewModel.startOfflineDemo {
                            navController.navigate(Routes.preflight(demoMode = true, warning = null)) {
                                popUpTo(Routes.CONNECTION) { inclusive = false }
                            }
                        }
                    }
                )
            }

            composable(Routes.COCKPIT) {
                val uiState by viewModel.cockpitState.collectAsState()

                LaunchedEffect(Unit) {
                    viewModel.startCockpitSession()
                }
                DisposableEffect(Unit) {
                    onDispose {
                        viewModel.exitCockpitSession()
                    }
                }

                CockpitScreen(
                    state = uiState,
                    apiClient = viewModel.apiClient,
                    streamClient = viewModel.mjpegStreamClient,
                    onDirectionPress = viewModel::onDirectionPress,
                    onDirectionRelease = viewModel::onDirectionRelease,
                    onArmClick = viewModel::onArmClick,
                    onDisarmClick = viewModel::onDisarmClick,
                    onClearFaultClick = viewModel::onClearFaultClick,
                    onStopClick = viewModel::onStopClick,
                    onAdjustRpm = viewModel::onAdjustRpm,
                    onOpenMission = viewModel::onOpenMission,
                    onCloseMission = viewModel::onCloseMission,
                    onOpenSettings = viewModel::onOpenSettings,
                    onCloseSettings = viewModel::onCloseSettings,
                    onApplyAllSettings = viewModel::onApplySettings,
                    onApplyPidOnly = viewModel::onApplyPidOnly,
                    onResetMap = viewModel::onResetMap,
                    onToggleDemoObstacle = viewModel::toggleDemoObstacle,
                    onExit = {
                        viewModel.exitCockpitSession()
                        navController.popBackStack(Routes.CONNECTION, inclusive = false)
                    }
                )
            }
        }
    }
}
