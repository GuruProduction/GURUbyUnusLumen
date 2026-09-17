package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Single

@Single
class SearchTasksUseCase(
    private val tasksRepository: TaskRepository
) {
    operator fun invoke(query: String): Flow<List<Task>> {
        return tasksRepository.searchTasks(query)
    }
}