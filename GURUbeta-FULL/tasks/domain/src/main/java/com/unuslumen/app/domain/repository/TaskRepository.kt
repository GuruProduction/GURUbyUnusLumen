// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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