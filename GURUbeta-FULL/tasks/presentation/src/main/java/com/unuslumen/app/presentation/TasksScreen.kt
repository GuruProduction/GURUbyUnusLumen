@file:OptIn(ExperimentalLayoutApi::class)
@file:Suppress("AssignedValueIsNeverRead")

package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
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
import com.unuslumen.app.ui.components.tasks.TaskCard
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.snackbar.showSnackbar
import com.unuslumen.app.ui.titleRes
import com.unuslumen.app.util.permissions.Permission
import com.unuslumen.app.util.permissions.rememberPermissionState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    navController: NavHostController,
    addTask: Boolean = false,
    viewModel: TasksViewModel = koinViewModel()
) {
    var orderSettingsVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val uiState = viewModel.tasksUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var openSheet by rememberSaveable {
        mutableStateOf(false)
    }
    val alarmPermissionState = rememberPermissionState(Permission.SCHEDULE_ALARMS)
    val scope = rememberCoroutineScope()
    val liquidState = rememberLiquidState()
    Scaffold(
        snackbarHost = { LocalisedSnackbarHost(snackbarHostState) },
        topBar = {
            guruAppBar(stringResource(R.string.tasks))
        },
        floatingActionButton = {
            AnimatedVisibility(!sheetState.isVisible) {
                LiquidFloatingActionButton(
                    onClick = {
                        openSheet = true
                    },
                    iconPainter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.add_task),
                    liquidState = liquidState
                )
            }
        },
    ) { paddingValues ->
        if (openSheet) ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { openSheet = false },
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = true
            )
        ) {
            AddTaskBottomSheetContent(
                onAddTask = {
                    viewModel.onEvent(TaskEvent.AddTask(it))
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) { openSheet = false }
                    }
                    focusRequester.freeFocus()
                },
                focusRequester
            )
        }
        LaunchedEffect(uiState.alarmError) {
            if (uiState.alarmError) {
                val snackbarResult = snackbarHostState.showSnackbar(R.string.no_alarm_permission, R.string.grant_permission)
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    alarmPermissionState.launchRequest()
                }
                viewModel.onEvent(TaskEvent.ErrorDisplayed)
            }
        }
        LaunchedEffect(true) {
            if (addTask) {
                openSheet = true
            }
        }
        if (uiState.tasks.isEmpty()) NoTasksMessage()
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .liquefiable(liquidState)
        ) {
            Column(
                Modifier.fillMaxWidth()
            ) {
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
                        navController.navigate(Screen.NotesScreen)
                    }) {
                        Icon(
                            modifier = Modifier.size(25.dp),
                            painter = painterResource(id = R.drawable.ic_search),
                            contentDescription = stringResource(R.string.search)
                        )
                    }
                }
                AnimatedVisibility(visible = orderSettingsVisible) {
                    TasksSettingsSection(
                        uiState.taskOrder,
                        uiState.showCompletedTasks,
                        onShowCompletedChange = {
                            viewModel.onEvent(
                                TaskEvent.ShowCompletedTasks(
                                    it
                                )
                            )
                        },
                        onOrderChange = {
                            viewModel.onEvent(TaskEvent.UpdateOrder(it))
                        }
                    )
                }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)
            ) {
                items(uiState.tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onComplete = {
                            viewModel.onEvent(
                                TaskEvent.CompleteTask(
                                    task,
                                    !task.isCompleted
                                )
                            )
                        },
                        onClick = {
                            navController.navigate(
                                Screen.TaskDetailScreen(
                                    taskId = task.id
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun NoTasksMessage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.no_tasks_message),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Image(
            modifier = Modifier.size(125.dp),
            painter = painterResource(id = R.drawable.tasks_img),
            contentDescription = stringResource(R.string.no_tasks_message),
            alpha = 0.7f
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksSettingsSection(
    order: Order,
    showCompleted: Boolean,
    onOrderChange: (Order) -> Unit,
    onShowCompletedChange: (Boolean) -> Unit
) {
    val orders = remember {
        listOf(
            Order.DateModified(),
            Order.DueDate(),
            Order.DateCreated(),
            Order.Alphabetical(),
            Order.Priority(),
            Order.Done()
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
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showCompleted, onCheckedChange = { onShowCompletedChange(it) })
            Text(
                text = stringResource(R.string.show_completed_tasks),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}