package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.Task

sealed class TaskDetailsEvent {
    data class ScreenOnStop(val task: Task): TaskDetailsEvent()
    data object DeleteTask : TaskDetailsEvent()
    data object ErrorDisplayed: TaskDetailsEvent()
    data object DueDateEnabled : TaskDetailsEvent()
}