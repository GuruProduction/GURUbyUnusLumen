@file:OptIn(ExperimentalLayoutApi::class)

package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.LiquidFloatingActionButton
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.titleRes
import com.unuslumen.app.util.date.formatTime
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JournalScreen(
    navController: NavHostController,
    viewModel: JournalViewModel = koinViewModel()
) {
    val uiState = viewModel.uiState
    var orderSettingsVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val liquidState = rememberLiquidState()
    Scaffold(
        topBar = {
            guruAppBar(
                title = stringResource(R.string.journal),
                actions = {
                    IconButton(onClick = {
                        navController.navigate(Screen.JournalChartScreen)
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chart),
                            contentDescription = stringResource(R.string.journal_chart),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            LiquidFloatingActionButton(
                onClick = {
                    navController.navigate(
                        Screen.JournalDetailScreen()
                    )
                },
                iconPainter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.add_entry),
                liquidState = liquidState
            )
        }
    ) { paddingValues ->
        if (uiState.entries.isEmpty()) {
            NoEntriesMessage()
        }
        Column(Modifier.padding(paddingValues).liquefiable(liquidState)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { orderSettingsVisible = !orderSettingsVisible }) {
                    Icon(
                        modifier = Modifier.size(25.dp),
                        painter = painterResource(R.drawable.ic_settings_sliders),
                        contentDescription = stringResource(R.string.order_by)
                    )
                }
                IconButton(onClick = {
                    navController.navigate(Screen.JournalSearchScreen)
                }) {
                    Icon(
                        modifier = Modifier.size(25.dp),
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = stringResource(R.string.search)
                    )
                }
            }
            AnimatedVisibility(visible = orderSettingsVisible) {
                JournalSettingsSection(
                    uiState.entriesOrder,
                    onOrderChange = {
                        viewModel.onEvent(JournalEvent.UpdateOrder(it))
                    },
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                uiState.entries.forEach { (day, entries) ->
                    stickyHeader {
                        Text(
                            text = day,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(bottom = 4.dp)
                                .padding(horizontal = 12.dp)
                        )
                    }
                    items(entries) { entry ->
                        JournalEntryItem(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            entry = entry,
                            timeText = entry.createdDate.formatTime(context),
                            onClick = {
                                navController.navigate(
                                    Screen.JournalDetailScreen(
                                        entry.id
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun JournalSettingsSection(order: Order, onOrderChange: (Order) -> Unit) {
    val orders = remember {
        listOf(
            Order.DateModified(),
            Order.DateCreated(),
            Order.Alphabetical()
        )
    }
    val orderTypes = remember {
        listOf(
            OrderType.ASC,
            OrderType.DESC
        )
    }
    Column(
        Modifier.background(color = MaterialTheme.colorScheme.background)
    ) {
        Text(
            text = stringResource(R.string.order_by),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
        FlowRow(
            modifier = Modifier.padding(end = 8.dp)
        ) {
            orders.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = order::class == it::class,
                        onClick = {
                            if (order != it)
                                onOrderChange(
                                    it.copyOrder(orderType = order.orderType)
                                )
                        }
                    )
                    Text(
                        text = stringResource(it.titleRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        HorizontalDivider()
        FlowRow {
            orderTypes.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = order.orderType == it,
                        onClick = {
                            if (order != it)
                                onOrderChange(
                                    order.copyOrder(it)
                                )
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(it.titleRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun NoEntriesMessage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.no_entries_message),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Image(
            modifier = Modifier.size(125.dp),
            painter = painterResource(id = R.drawable.journal_img),
            contentDescription = stringResource(R.string.no_entries_message),
            alpha = 0.7f
        )
    }
}