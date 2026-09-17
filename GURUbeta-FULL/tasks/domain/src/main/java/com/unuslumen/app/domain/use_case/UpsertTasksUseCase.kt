package com.unuslumen.app.domain.use_case

import com.unuslumen.app.alarm.use_case.UpsertAlarmUseCase
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.repository.TaskRepository
import com.unuslumen.app.widget.WidgetUpdater
import org.koin.core.annotation.Factory
import kotlin.time.Clock.System.now

@Factory
class UpsertTasksUseCase(
    private val tasksRepository: TaskRepository,
    private val upsertAlarm: UpsertAlarmUseCase,
    private val widgetUpdater: WidgetUpdater
) {
    suspend operator fun invoke(
        tasks: List<Task>,
        updateWidget: Boolean = true
    ) {
        val nowMillis = now().toEpochMilliseconds()
        val finalTasks = tasks.map { task ->
            if (task.dueDate != 0L && task.dueDate > nowMillis) {
                val alarmId = upsertAlarm(task.alarmId ?: 0, task.dueDate)
                task.copy(alarmId = alarmId)
            } else {
                task
            }
        }

        tasksRepository.upsertTasks(finalTasks)
        if (updateWidget) widgetUpdater.updateAll(WidgetUpdater.WidgetType.Tasks)
    }
}
