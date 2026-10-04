// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.alarm.use_case.DeleteAlarmUseCase
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.repository.TaskRepository
import org.koin.core.annotation.Single

@Single
class DeleteTaskUseCase(
    private val taskRepository: TaskRepository,
    private val deleteAlarm: DeleteAlarmUseCase
) {
    suspend operator fun invoke(task: Task) {
        taskRepository.deleteTask(task)
        if (task.dueDate != 0L && task.alarmId != null) {
            deleteAlarm(task.alarmId)
        }
    }
}