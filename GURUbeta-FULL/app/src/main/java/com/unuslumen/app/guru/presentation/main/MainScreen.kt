// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unuslumen.app.guru.presentation.app_lock.AppLockManager
import com.unuslumen.app.guru.presentation.main.components.MainBottomBar
import com.unuslumen.app.guru.presentation.main.components.NavigationGraph
import com.unuslumen.app.guru.presentation.main.components.BottomNavItem
import com.unuslumen.app.ui.navigation.Screen

@Composable
fun MainScreen(
    startUpScreen: Screen,
    mainNavController: NavHostController,
    appLockManager: AppLockManager,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val bottomNavItems =
        listOf(BottomNavItem.Dashboard, BottomNavItem.Lobby, BottomNavItem.Settings)
    Scaffold(
        modifier = modifier,
        bottomBar = {
            MainBottomBar(navController = navController, items = bottomNavItems)
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) {  paddingValues ->
        NavigationGraph(
            modifier = Modifier.padding(paddingValues),
            navController = navController,
            mainNavController = mainNavController,
            startUpScreen = startUpScreen,
            appLockManager
        )
    }
}