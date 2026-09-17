package com.unuslumen.app.guru.presentation.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.unuslumen.app.presentation.CalendarDashboardWidget
import com.unuslumen.app.presentation.MoodCircularBar
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import org.koin.androidx.compose.koinViewModel


@Composable
fun DashboardScreen(
    navController: NavHostController,
    viewModel: MainViewModel = koinViewModel()
) {
    Scaffold(
        topBar = {
            guruAppBar(stringResource(R.string.dashboard))
        }
    ) {paddingValues ->
        LaunchedEffect(true) { viewModel.onDashboardEvent(DashboardEvent.InitAll) }
        LazyColumn(contentPadding = paddingValues) {
            item {
                CalendarDashboardWidget(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.5f),
                    events = viewModel.uiState.dashBoardEvents,
                    onClick = {
                        navController.navigate(
                            Screen.CalendarScreen
                        )
                    },
                    onPermission = {
                        viewModel.onDashboardEvent(DashboardEvent.ReadPermissionChanged(it))
                    },
                    onAddEventClicked = {
                        navController.navigate(
                            Screen.CalendarEventDetailsScreen()
                        )
                    },
                    onEventClicked = {
                        navController.navigate(
                            Screen.CalendarEventDetailsScreen(
                                it.id
                            )
                        )
                    }
                )
            }
            item {
                Row {
                    MoodCircularBar(
                        entries = viewModel.uiState.dashBoardEntries,
                        showPercentage = false,
                        modifier = Modifier.weight(1f, fill = true),
                        onClick = {
                            navController.navigate(
                                Screen.JournalChartScreen
                            )
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(65.dp)) }
        }
    }
}