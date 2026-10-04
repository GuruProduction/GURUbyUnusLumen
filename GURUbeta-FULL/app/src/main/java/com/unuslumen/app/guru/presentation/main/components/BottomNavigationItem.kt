// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main.components

import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.navigation.Screen

sealed class BottomNavItem(val title: Int, val icon: Int, val iconSelected: Int, val screen: Screen){

    data object Dashboard : BottomNavItem(R.string.dashboard, R.drawable.ic_home, R.drawable.ic_home_filled,
        Screen.DashboardScreen
    )
    data object Lobby : BottomNavItem(R.string.lobby, R.drawable.ic_lobby, R.drawable.ic_lobby_filled,
        Screen.LobbyScreen
    )
    data object Settings: BottomNavItem(R.string.settings, R.drawable.ic_settings, R.drawable.ic_settings_filled,
        Screen.SettingsScreen
    )

}