package com.unuslumen.app.data

import com.unuslumen.app.database.dao.TaskDao
import com.unuslumen.app.database.entity.toTask
import com.unuslumen.app.database.entity.toTaskEntity
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.repository.TaskRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
) : TaskRepository {

    override fun getAllTasks(): Flow<List<Task>> {
        return taskDao.getAllTasks()
            .flowOn(ioDispatcher)
            .map { tasks ->
                tasks.map { it.toTask() }
            }
    }

    override suspend fun getTaskById(id: String): Task? {
        return withContext(ioDispatcher) {
            taskDao.getTask(id)?.toTask()
        }
    }

    override suspend fun getTaskByAlarm(alarmId: Int): Task? {
        return withContext(ioDispatcher) {
            taskDao.getTaskByAlarm(alarmId)?.toTask()
        }
    }

    override fun searchTasks(title: String): Flow<List<Task>> {
        return taskDao.getTasksByTitle(title)
            .flowOn(ioDispatcher)
            .map { tasks ->
            tasks.map { it.toTask() }
        }
    }

    override suspend fun upsertTask(task: Task) {
        return withContext(ioDispatcher) {
            taskDao.upsertTask(task.toTaskEntity())
        }
    }

    override suspend fun upsertTasks(tasks: List<Task>) {
        withContext(ioDispatcher) {
            taskDao.upsertTasks(tasks.map { it.toTaskEntity() })
        }
    }

    override suspend fun updateTask(task: Task) {
        withContext(ioDispatcher) {
            taskDao.updateTask(task.toTaskEntity())
        }
    }

    override suspend fun completeTask(id: String, completed: Boolean) {
        withContext(ioDispatcher) {
            taskDao.updateCompleted(id, completed)
        }
    }

    override suspend fun deleteTask(task: Task) {
        withContext(ioDispatcher) {
            taskDao.deleteTask(task.toTaskEntity())
        }
    }

}
