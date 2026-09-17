package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    suspend fun getAllFullTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE alarmId = :alarmId")
    suspend fun getTaskByAlarm(alarmId: Int): TaskEntity?

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :title || '%'")
    fun getTasksByTitle(title: String): Flow<List<TaskEntity>>

    @Upsert
    suspend fun upsertTask(task: TaskEntity)

    @Upsert
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("UPDATE tasks SET is_completed = :completed WHERE id = :id")
    suspend fun updateCompleted(id: String, completed: Boolean)

}