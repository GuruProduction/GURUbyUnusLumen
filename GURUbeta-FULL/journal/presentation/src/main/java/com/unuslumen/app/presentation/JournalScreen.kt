// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
@file:OptIn(ExperimentalLayoutApi::class)

package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.ui.titleRes
import com.unuslumen.app.util.date.formatTime
import org.koin.androidx.compose.koinViewModel

/**
 * The journal's front door, in the house language.
 *
 * WHAT IT WAS. A Material page: two icon buttons floating over the list, a liquid
 * floating button hovering above the last card, rounded elevated cards for each
 * entry and a row of radio buttons for the order. Beside the lobby it read as a
 * settings page that happened to share the background.
 *
 * WHAT IT IS NOW. The add door is a full-width printed block at the top of the list
 * rather than a button floating over it, the day is an ink band, the order is chosen
 * by pressing a block that fills with ink, and the chart is a printed block in the
 * masthead. Every event, navigation target and string resource is the one the page
 * already used: this is a skin over the same code.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JournalScreen(
    navController: NavHostController,
    viewModel: JournalViewModel = koinViewModel()
) {
    val uiState = viewModel.uiState
    var orderSettingsVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = stringResource(R.string.journal),
                actions = {
                    MemphisSurface(
                        modifier = Modifier.clickable {
                            navController.navigate(Screen.JournalChartScreen)
                        },
                        color = Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(Modifier.padding(7.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chart),
                                contentDescription = stringResource(R.string.journal_chart),
                                modifier = Modifier.size(22.dp),
                                tint = Memphis.Ink,
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.entries.isEmpty()) {
            NoEntriesMessage()
        }
        Column(Modifier.padding(paddingValues)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageButton(
                    label = stringResource(R.string.order_by),
                    onClick = { orderSettingsVisible = !orderSettingsVisible },
                )
                PageButton(
                    label = stringResource(R.string.search),
                    onClick = {
                        navController.navigate(Screen.JournalSearchScreen)
                    },
                )
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
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 24.dp),
                modifier = Modifier.weight(1f)
            ) {
                item(key = "new-entry") {
                    NewEntryBar(
                        onClick = {
                            navController.navigate(
                                Screen.JournalDetailScreen()
                            )
                        }
                    )
                }
                // Each entry takes its colour from the same rotation the lobby
                // uses, counted per day so a short day still starts on ink.
                uiState.entries.forEach { (day, entries) ->
                    stickyHeader {
                        DayBand(day)
                    }
                    itemsIndexed(entries) { index, entry ->
                        JournalEntryItem(
                            entry = entry,
                            ink = Memphis.inkFor(index),
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

/** The add door: a full-width printed bar, so nothing floats over the page. */
@Composable
private fun NewEntryBar(onClick: () -> Unit) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = Memphis.Ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+ New entry",
                style = posterStyle(14f, Memphis.Paper),
            )
        }
    }
}

/** The day, as the ink band that separates one group of entries from the next. */
@Composable
private fun DayBand(label: String) {
    Text(
        text = label.uppercase(),
        style = captionStyle(9.5f, Memphis.Paper),
        modifier = Modifier
            .fillMaxWidth()
            .background(Memphis.Ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** One control on the page, as a printed block rather than a Material icon button. */
@Composable
private fun PageButton(
    label: String,
    onClick: () -> Unit,
) {
    MemphisSurface(
        modifier = Modifier.clickable { onClick() },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Box(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.uppercase(),
                style = captionStyle(9f),
                maxLines = 1,
            )
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
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.order_by).uppercase(),
            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            orders.forEach { option ->
                OrderChip(
                    label = stringResource(option.titleRes),
                    on = order::class == option::class,
                    onClick = {
                        if (order != option)
                            onOrderChange(
                                option.copyOrder(orderType = order.orderType)
                            )
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            orderTypes.forEach { option ->
                OrderChip(
                    label = stringResource(option.titleRes),
                    on = order.orderType == option,
                    onClick = {
                        if (order != option)
                            onOrderChange(
                                order.copyOrder(option)
                            )
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * One order option, as a printed block that fills with ink when chosen.
 *
 * The chosen block is the only one on the row carrying paper type, so which option
 * is in force reads before any word does. Pressing it makes exactly the call the
 * radio button made.
 */
@Composable
private fun OrderChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(if (on) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = captionStyle(9f, if (on) Memphis.Paper else Memphis.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NoEntriesMessage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.no_entries_message).uppercase(),
            style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Image(
            modifier = Modifier.size(125.dp),
            painter = painterResource(id = R.drawable.journal_img),
            contentDescription = stringResource(R.string.no_entries_message),
            alpha = 0.5f
        )
    }
}
