package com.unuslumen.app.presentation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.use_case.GetAllTasksUseCase
import com.unuslumen.app.domain.use_case.SearchTasksUseCase
import com.unuslumen.app.domain.use_case.UpdateTaskCompletedUseCase
import com.unuslumen.app.domain.use_case.UpsertTaskUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.toInt
import com.unuslumen.app.preferences.domain.model.toOrder
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.snackbar.showSnackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class TasksViewModel(
    private val addTask: UpsertTaskUseCase,
    private val getAllTasks: GetAllTasksUseCase,
    private val completeTask: UpdateTaskCompletedUseCase,
    getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
    private val searchTasksUseCase: SearchTasksUseCase
) : ViewModel() {

    var tasksUiState by mutableStateOf(UiState())
        private set

    private var getTasksJob: Job? = null
    private var searchTasksJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                getPreference(
                    intPreferencesKey(PrefsConstants.TASKS_ORDER_KEY),
                    Order.DateModified(OrderType.ASC).toInt()
                ),
                getPreference(
                    booleanPreferencesKey(PrefsConstants.SHOW_COMPLETED_TASKS_KEY),
                    false
                )
            ) { order, showCompleted ->
                getTasks(order.toOrder(), showCompleted)
            }.collect()
        }
    }

    fun onEvent(event: TaskEvent) {
        when (event) {
            is TaskEvent.AddTask -> {
                viewModelScope.launch {
                    if (event.task.title.isNotBlank()) {
                        val scheduleSuccess = addTask(event.task)
                        if (!scheduleSuccess) tasksUiState = tasksUiState.copy(alarmError = true)
                    } else {
                        tasksUiState.snackbarHostState.showSnackbar(R.string.error_empty_title)
                    }
                }
            }

            is TaskEvent.CompleteTask -> viewModelScope.launch {
                completeTask(event.task, event.complete)
            }

            TaskEvent.ErrorDisplayed -> {
                tasksUiState = tasksUiState.copy(alarmError = false)
            }

            is TaskEvent.UpdateOrder -> viewModelScope.launch {
                savePreference(
                    intPreferencesKey(PrefsConstants.TASKS_ORDER_KEY),
                    event.order.toInt()
                )
            }

            is TaskEvent.ShowCompletedTasks -> viewModelScope.launch {
                savePreference(
                    booleanPreferencesKey(PrefsConstants.SHOW_COMPLETED_TASKS_KEY),
                    event.showCompleted
                )
            }

            is TaskEvent.SearchTasks -> {
                viewModelScope.launch {
                    searchTasks(event.query)
                }
            }
        }
    }

    data class UiState(
        val tasks: List<Task> = emptyList(),
        val taskOrder: Order = Order.DateModified(OrderType.ASC),
        val showCompletedTasks: Boolean = false,
        val alarmError: Boolean = false,
        val searchTasks: List<Task> = emptyList(),
        val snackbarHostState: SnackbarHostState = SnackbarHostState()
    )

    private fun getTasks(order: Order, showCompleted: Boolean) {
        getTasksJob?.cancel()
        getTasksJob = getAllTasks(order)
            .map { list ->
                if (showCompleted)
                    list
                else
                    list.filter { !it.isCompleted }
            }.onEach { tasks ->
                tasksUiState = tasksUiState.copy(
                    tasks = tasks,
                    taskOrder = order,
                    showCompletedTasks = showCompleted
                )
            }.launchIn(viewModelScope)
    }

    private fun searchTasks(query: String) {
        searchTasksJob?.cancel()
        searchTasksJob = searchTasksUseCase(query).onEach { tasks ->
            tasksUiState = tasksUiState.copy(
                searchTasks = tasks
            )
        }.launchIn(viewModelScope)
    }
}