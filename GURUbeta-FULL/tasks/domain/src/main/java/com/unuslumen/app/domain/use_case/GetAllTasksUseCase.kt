package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.repository.TaskRepository
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class GetAllTasksUseCase(
    private val tasksRepository: TaskRepository,
    @Named("defaultDispatcher") private val defaultDispatcher: CoroutineDispatcher
) {
    operator fun invoke(order: Order, showCompleted: Boolean = true): Flow<List<Task>> {
        return tasksRepository.getAllTasks().map { tasks ->
            when (order.orderType) {
                is OrderType.ASC -> {
                    when (order) {
                        is Order.Alphabetical -> tasks.sortedBy { it.title }
                        is Order.DateCreated -> tasks.sortedBy { it.createdDate }
                        is Order.DateModified -> tasks.sortedBy { it.updatedDate }
                        is Order.Priority -> tasks.sortedBy { it.priority }
                        is Order.DueDate -> tasks.sortedWith(compareBy({ it.dueDate == 0L }, { it.dueDate }))
                        is Order.Done -> tasks.sortedBy { it.isCompleted }
                    }
                }
                is OrderType.DESC -> {
                    when (order) {
                        is Order.Alphabetical -> tasks.sortedByDescending { it.title }
                        is Order.DateCreated -> tasks.sortedByDescending { it.createdDate }
                        is Order.DateModified -> tasks.sortedByDescending { it.updatedDate }
                        is Order.Priority -> tasks.sortedByDescending { it.priority }
                        is Order.DueDate -> tasks.sortedWith(compareBy({ it.dueDate == 0L }, { it.dueDate })).reversed()
                        is Order.Done -> tasks.sortedByDescending { it.isCompleted }
                    }
                }
            }
        }.map { list ->
            if (showCompleted)
                list
            else
                list.filter { !it.isCompleted }
        }.flowOn(defaultDispatcher)
    }
}