package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.Task
import kotlinx.coroutines.flow.Flow

interface TaskRepository {

    fun getAllTasks(): Flow<List<Task>>

    suspend fun getTaskById(id: String): Task?

    suspend fun getTaskByAlarm(alarmId: Int): Task?

    fun searchTasks(title: String): Flow<List<Task>>

    suspend fun upsertTask(task: Task)

    suspend fun upsertTasks(tasks: List<Task>)

    suspend fun updateTask(task: Task)

    suspend fun completeTask(id: String, completed: Boolean)

    suspend fun deleteTask(task: Task)

}