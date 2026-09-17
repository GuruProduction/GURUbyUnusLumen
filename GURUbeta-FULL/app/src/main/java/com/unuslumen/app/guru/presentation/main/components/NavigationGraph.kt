package com.unuslumen.app.guru.presentation.main.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.unuslumen.app.guru.presentation.app_lock.AppLockManager
import com.unuslumen.app.guru.presentation.main.DashboardScreen
import com.unuslumen.app.guru.presentation.main.SettingsScreen
import com.unuslumen.app.guru.presentation.main.LobbyScreen
import com.unuslumen.app.ui.navigation.Screen

@Composable
fun NavigationGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    mainNavController: NavHostController,
    startUpScreen: Screen,
    appLockManager: AppLockManager
) {
    NavHost(modifier = modifier, navController = navController, startDestination = startUpScreen){

        composable<Screen.DashboardScreen>(
            enterTransition = { fadeIn(tween(0)) },
            exitTransition = { fadeOut(tween(0)) },
        ) {
            DashboardScreen(mainNavController)
        }
        composable<Screen.LobbyScreen>(
            enterTransition = { fadeIn(tween(0)) },
            exitTransition = { fadeOut(tween(0)) },
        ) {
            LobbyScreen(mainNavController)
        }
        composable<Screen.SettingsScreen>(
            enterTransition = { fadeIn(tween(0)) },
            exitTransition = { fadeOut(tween(0)) },
        ) {
            SettingsScreen(mainNavController, appLockManager)
        }
    }
}