package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.TaskRepository
import org.koin.core.annotation.Factory

@Factory
class GetTaskByAlarmUseCase(
    private val tasksRepository: TaskRepository
) {
    suspend operator fun invoke(alarmId: Int) = tasksRepository.getTaskByAlarm(alarmId)
}