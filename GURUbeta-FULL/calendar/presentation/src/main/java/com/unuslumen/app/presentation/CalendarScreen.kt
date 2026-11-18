// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.Calendar
import com.unuslumen.app.domain.model.CalendarDay
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.util.date.HOUR_MILLIS
import com.unuslumen.app.util.date.currentLocalDate
import com.unuslumen.app.util.date.monthName
import com.unuslumen.app.util.date.now
import com.unuslumen.app.util.date.withTimeFrom
import com.unuslumen.app.util.permissions.Permission
import com.unuslumen.app.util.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import kotlinx.datetime.number
import org.koin.androidx.compose.koinViewModel

/**
 * The calendar room, in the house language.
 *
 * WHAT IT WAS. A Material page: two icon buttons in the app bar, a liquid floating
 * button hovering over the last row, a soft lavender fill on the chosen day, rounded
 * elevated event cards, and a dropdown drawn in Material's own menu. None of that
 * belonged on a printed page and the room read as a different product.
 *
 * WHAT IT IS NOW. Printed blocks for the controls, an ink fill for the chosen day,
 * ruled event rows carrying the calendar's colour as a spine, and a sheet in the same
 * grammar as the lobby's add-a-room sheet for the calendars list.
 *
 * Every event, every permission call and every navigation target is unchanged. This
 * is a skin over the same code.
 */
@Composable
fun CalendarScreen(
    navController: NavHostController,
    viewModel: CalendarViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listViewState = rememberLazyListState()
    val dayEventsState = rememberLazyListState()
    var settingsVisible by remember { mutableStateOf(false) }
    val readCalendarPermissionState = rememberPermissionState(
        permission = Permission.READ_CALENDAR
    )
    val today = remember { currentLocalDate() }
    val viewMode = if (state.isMonthView) CalendarViewMode.Month else CalendarViewMode.List

    val months = state.months
    val loadedMonths = state.loadedMonths
    val currentMonth = state.currentMonth
    val selectedDate = state.selectedDate

    val selectedDay = remember(selectedDate, currentMonth, loadedMonths[selectedDate.month.number]) {
        loadedMonths[selectedDate.month.number]?.days?.firstOrNull { it.date == selectedDate }
            ?: CalendarDay(
                date = selectedDate,
                isCurrentMonth = selectedDate.year == currentMonth.year && selectedDate.month == currentMonth.month,
                events = emptyList()
            )
    }

    val scope = rememberCoroutineScope()

    val listMonthLabel by remember(state.events) {
        derivedStateOf {
            if (state.events.isEmpty()) ""
            else {
                val values = state.events.values.toList()
                val index = listViewState.firstVisibleItemIndex.coerceIn(0, values.lastIndex)
                values.getOrNull(index)?.firstOrNull()?.start?.monthName().orEmpty()
            }
        }
    }

    val selectedMonthLabel = when (viewMode) {
        CalendarViewMode.Month -> currentMonth.monthName()
        CalendarViewMode.List -> listMonthLabel
    }

    LaunchedEffect(viewMode) {
        if (viewMode == CalendarViewMode.Month && settingsVisible) {
            settingsVisible = false
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = stringResource(R.string.calendar),
                stamp = selectedMonthLabel.ifEmpty { null },
                actions = {
                    ViewModeToggleButton(
                        mode = viewMode,
                        onToggle = {
                            val nextIsMonth = viewMode == CalendarViewMode.List
                            viewModel.onEvent(CalendarViewModelEvent.ViewModeChanged(nextIsMonth))
                        }
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (readCalendarPermissionState.isGranted) {
                LaunchedEffect(true) {
                    viewModel.onEvent(
                        CalendarViewModelEvent.ReadPermissionChanged(readCalendarPermissionState.isGranted)
                    )
                }

                CalendarControlRow(
                    viewMode = viewMode,
                    monthLabel = selectedMonthLabel,
                    months = months,
                    onMonthSelected = { selected ->
                        scope.launch {
                            val targetIndex = state.events.values.indexOfFirst {
                                it.firstOrNull()?.start?.monthName() == selected
                            }
                            if (targetIndex >= 0) {
                                listViewState.scrollToItem(targetIndex)
                            }
                        }
                    },
                    onToggleCalendars = { settingsVisible = !settingsVisible },
                    onAdd = {
                        val createEventStartMillis =
                            if (viewMode == CalendarViewMode.Month) {
                                selectedDate.withTimeFrom(now() + HOUR_MILLIS)
                            } else {
                                null
                            }
                        navController.navigate(
                            Screen.CalendarEventDetailsScreen(
                                eventId = null,
                                initialStartMillis = createEventStartMillis
                            )
                        )
                    },
                )

                if (viewMode == CalendarViewMode.List) {
                    AnimatedVisibility(visible = settingsVisible) {
                        CalendarSettingsSection(
                            calendars = state.calendars,
                            onCalendarClicked = {
                                viewModel.onEvent(CalendarViewModelEvent.IncludeCalendar(it))
                            }
                        )
                    }
                    CalendarListView(
                        modifier = Modifier.fillMaxSize(),
                        state = listViewState,
                        events = state.events,
                        onEventClick = { event ->
                            navController.navigate(Screen.CalendarEventDetailsScreen(event.id))
                        }
                    )
                } else {
                    MonthlyCalendar(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        loadedMonths = loadedMonths,
                        onLoadMonth = viewModel::loadMonth,
                        initialMonth = currentMonth,
                        selectedDate = selectedDate,
                        today = today,
                        firstDayOfWeek = state.firstDayOfWeek,
                        onDaySelected = { date ->
                            viewModel.onEvent(CalendarViewModelEvent.SelectedDateChanged(date))
                        },
                        onMonthChanged = {
                            viewModel.onEvent(CalendarViewModelEvent.MonthChanged(it))
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    DayEventsList(
                        modifier = Modifier.fillMaxSize(),
                        state = dayEventsState,
                        selectedDate = selectedDay,
                        onEventClick = { event ->
                            navController.navigate(Screen.CalendarEventDetailsScreen(event.id))
                        }
                    )
                }
            } else {
                NoReadCalendarPermissionMessage(
                    shouldShowRationale = readCalendarPermissionState.shouldShowRationale,
                    onOpenSettings = { readCalendarPermissionState.openAppSettings() },
                    onRequest = { readCalendarPermissionState.launchRequest() }
                )
            }
        }
    }
}

/**
 * The controls on one line, as printed blocks.
 *
 * The month is a door that opens the month list in the list view, the calendars control
 * opens the include list, and add is the one filled block on the line. All three were
 * icon buttons before, which is what made the room look like a settings page.
 */
@Composable
private fun CalendarControlRow(
    viewMode: CalendarViewMode,
    monthLabel: String,
    months: List<String>,
    onMonthSelected: (String) -> Unit,
    onToggleCalendars: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (viewMode == CalendarViewMode.List && months.isNotEmpty()) {
            MonthDropDownMenu(
                selectedMonth = monthLabel.ifEmpty { "Month" },
                months = months,
                onMonthSelected = onMonthSelected,
            )
        }
        if (viewMode == CalendarViewMode.List) {
            PageButton(
                label = stringResource(R.string.include_calendars),
                onClick = onToggleCalendars,
            )
        }
        PageButton(
            label = stringResource(R.string.add_event),
            onClick = onAdd,
            filled = true,
        )
    }
}

/** One control on the page, as a printed block. */
@Composable
private fun PageButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    MemphisSurface(
        modifier = Modifier.clickable { onClick() },
        color = if (filled) Memphis.Ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Box(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.uppercase(),
                style = captionStyle(9f, if (filled) Memphis.Paper else Memphis.Ink),
                maxLines = 1,
            )
        }
    }
}

@Composable
fun NoReadCalendarPermissionMessage(
    shouldShowRationale: Boolean,
    onOpenSettings: () -> Unit,
    onRequest: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.no_read_calendar_permission_message),
            style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.7f)),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        PageButton(
            label = if (shouldShowRationale) {
                stringResource(R.string.go_to_settings)
            } else {
                stringResource(R.string.grant_permission)
            },
            onClick = { if (shouldShowRationale) onOpenSettings() else onRequest() },
            filled = true,
        )
    }
}

@Composable
private fun CalendarListView(
    modifier: Modifier = Modifier,
    state: LazyListState,
    events: Map<String, List<CalendarEvent>>,
    onEventClick: (CalendarEvent) -> Unit
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        events.forEach { (day, dayEvents) ->
            item(key = day) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        text = day.substring(0, day.indexOf(",")).uppercase(),
                        style = posterStyle(14f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    dayEvents.forEach { event ->
                        CalendarEventItem(event = event, onClick = onEventClick)
                    }
                }
            }
        }
    }
}

/**
 * The month list, opened from a printed block.
 *
 * The Material dropdown stays, because it is a popup that belongs to the system rather
 * than to the page, and its contents are now set in the page's own type so it does not
 * read as a foreign surface.
 */
@Composable
fun MonthDropDownMenu(
    selectedMonth: String,
    months: List<String>,
    onMonthSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        MemphisSurface(
            modifier = Modifier.clickable { expanded = true },
            color = Memphis.Paper,
            borderWidth = 2.5.dp,
            shadowOffset = 3.dp,
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(targetState = selectedMonth, label = "") { month ->
                    Text(
                        text = month.uppercase(),
                        style = captionStyle(9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            months.forEach {
                DropdownMenuItem(
                    onClick = {
                        onMonthSelected(it)
                        expanded = false
                    },
                    text = { Text(text = it.uppercase(), style = captionStyle(9.5f)) })
            }
        }
    }
}

/**
 * The list/month switch, as a printed block carrying the icon of the view you would get.
 *
 * It stays an icon because the two states are shapes a person recognises, but it is
 * mounted on a paper block with an ink border so it belongs to the page.
 */
@Composable
private fun ViewModeToggleButton(
    mode: CalendarViewMode,
    onToggle: () -> Unit
) {
    MemphisSurface(
        modifier = Modifier.clickable { onToggle() },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(Modifier.padding(7.dp)) {
            Icon(
                painter = painterResource(
                    if (mode == CalendarViewMode.List) R.drawable.ic_list_view else R.drawable.ic_monthly_view
                ),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Memphis.Ink,
            )
        }
    }
}

/**
 * The calendars to include, on a printed sheet.
 *
 * Each calendar is a row of paper with its own colour as a swatch, and tapping the row
 * toggles it exactly as the checkbox did. Nested calendars keep the dropdown that
 * reveals them, because that is a disclosure rather than a control.
 */
@Composable
fun CalendarSettingsSection(
    calendars: Map<String, List<Calendar>>,
    onCalendarClicked: (Calendar) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Memphis.Ink)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                text = stringResource(R.string.include_calendars).uppercase(),
                style = captionStyle(9.5f, Memphis.Paper),
            )
        }

        calendars.keys.forEach { calendar ->
            var expanded by remember { mutableStateOf(false) }

            Column(Modifier.padding(top = 8.dp)) {
                MemphisSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = calendar.uppercase(),
                            style = captionStyle(9.5f),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (expanded) "HIDE" else "SHOW",
                            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                        )
                    }
                }

                if (expanded) {
                    calendars[calendar]?.forEach { subCalendar ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onCalendarClicked(subCalendar) }
                                .padding(start = 6.dp, top = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .background(
                                        if (subCalendar.included) {
                                            Color(subCalendar.color)
                                        } else {
                                            Memphis.Paper
                                        }
                                    )
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = subCalendar.name,
                                style = captionStyle(9f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
