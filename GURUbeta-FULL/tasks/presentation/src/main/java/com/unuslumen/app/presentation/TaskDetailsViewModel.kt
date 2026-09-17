package com.unuslumen.app.presentation

import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.use_case.CanScheduleAlarmsUseCase
import com.unuslumen.app.domain.use_case.DeleteTaskUseCase
import com.unuslumen.app.domain.use_case.GetTaskByIdUseCase
import com.unuslumen.app.domain.use_case.UpsertTaskUseCase
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.snackbar.showSnackbar
import com.unuslumen.app.util.date.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class TaskDetailsViewModel(
    private val getTask: GetTaskByIdUseCase,
    private val upsertTask: UpsertTaskUseCase,
    private val deleteTask: DeleteTaskUseCase,
    private val canScheduleAlarms: CanScheduleAlarmsUseCase,
    @Named("applicationScope") private val applicationScope: CoroutineScope,
    taskId: String
) : ViewModel() {

    private val _taskDetailsUiState = MutableStateFlow(TaskDetailsUiState())
    val taskDetailsUiState = _taskDetailsUiState.asStateFlow()

    init {
        viewModelScope.launch {
            val task = getTask(taskId)
            if (taskId.isNotBlank() && task == null) {
                taskDetailsUiState.value.snackbarHostState.showSnackbar(R.string.error_item_not_found)
            }
            _taskDetailsUiState.update {
                it.copy(
                   task = task
                )
            }
        }
    }

    fun onEvent(event: TaskDetailsEvent) {
        when (event) {

            TaskDetailsEvent.ErrorDisplayed -> {
                _taskDetailsUiState.update { it.copy(alarmError = false) }
            }
            // Using applicationScope to avoid cancelling when the user exits the screen
            // and the view model is cleared before the job finishes
            is TaskDetailsEvent.ScreenOnStop -> applicationScope.launch {
                if (!taskDetailsUiState.value.navigateUp) {
                    if (taskChanged(taskDetailsUiState.value.task!!, event.task)) {
                        val newTask = taskDetailsUiState.value.task!!.copy(
                            title = event.task.title.ifBlank { "Untitled" },
                            description = event.task.description,
                            dueDate = event.task.dueDate,
                            priority = event.task.priority,
                            subTasks = event.task.subTasks,
                            recurring = event.task.recurring,
                            frequency = event.task.frequency,
                            frequencyAmount = event.task.frequencyAmount,
                            isCompleted = event.task.isCompleted,
                            updatedDate = now()
                        )
                        upsertTask(
                            task = newTask,
                            previousTask = taskDetailsUiState.value.task
                        )
                        _taskDetailsUiState.update { it.copy(task = newTask) }
                    }
                }
            }

            is TaskDetailsEvent.DeleteTask -> viewModelScope.launch {
                deleteTask(taskDetailsUiState.value.task!!)
                _taskDetailsUiState.update { it.copy(navigateUp = true) }
            }

            TaskDetailsEvent.DueDateEnabled -> {
                if (!canScheduleAlarms()) {
                    _taskDetailsUiState.update { it.copy(alarmError = true) }
                }
            }
        }
    }

    data class TaskDetailsUiState(
        val task: Task? = null,
        val navigateUp: Boolean = false,
        val alarmError: Boolean = false,
        val snackbarHostState: SnackbarHostState = SnackbarHostState()
    )

    private fun taskChanged(
        task: Task,
        newTask: Task
    ): Boolean {
        return task.title != newTask.title ||
                task.description != newTask.description ||
                task.dueDate != newTask.dueDate ||
                task.isCompleted != newTask.isCompleted ||
                task.priority != newTask.priority ||
                task.subTasks != newTask.subTasks ||
                task.recurring != newTask.recurring ||
                task.frequency != newTask.frequency ||
                task.frequencyAmount != newTask.frequencyAmount
    }

}