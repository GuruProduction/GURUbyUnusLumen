package com.unuslumen.app.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.AnimatedTabIndicator
import org.koin.androidx.compose.koinViewModel

@Composable
fun JournalChartScreen(
    viewModel: JournalViewModel = koinViewModel()
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .padding(WindowInsets.systemBars.asPaddingValues()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val state = viewModel.uiState
        var monthly by remember { mutableStateOf(true) }
        MonthlyOrYearlyTab {
            viewModel.onEvent(JournalEvent.ChangeChartEntriesRange(it))
            monthly = it
        }
        MoodCircularBar(entries = state.chartEntries)
        MoodFlowChart(entries = state.chartEntries, monthly)
    }
}

@Composable
fun MonthlyOrYearlyTab(
    onChange: (Boolean) -> Unit
) {
    var selected by remember { mutableIntStateOf(R.string.last_30_days) }
    LaunchedEffect(true){
        onChange(true)
    }
    PrimaryTabRow(
        selectedTabIndex = if (selected == R.string.last_30_days) 0 else 1,
        indicator = {
            AnimatedTabIndicator(Modifier.tabIndicatorOffset(if (selected == R.string.last_30_days) 0 else 1))
        },
        divider = {},
        modifier = Modifier.clip(RoundedCornerShape(14.dp))
    ) {
        Tab(
            text = { Text(
                stringResource(R.string.last_30_days),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ) },
            selected = selected == R.string.last_30_days,
            onClick = {
                selected = R.string.last_30_days
                onChange(true)
            },
        )
        Tab(
            text = { Text(
                stringResource(R.string.last_year),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ) },
            selected = selected == R.string.last_year,
            onClick = {
                selected = R.string.last_year
                onChange(false)
            }
        )
    }
}