// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    
    // ==================== Project CRUD ====================
    
    @Query("SELECT * FROM projects ORDER BY updated_date DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>
    
    @Query("SELECT * FROM projects WHERE is_active = 1 ORDER BY updated_date DESC")
    fun getActiveProjects(): Flow<List<ProjectEntity>>
    
    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProject(projectId: String): ProjectEntity?
    
    @Query("SELECT * FROM projects WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'")
    suspend fun searchProjects(query: String): List<ProjectEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long
    
    @Update
    suspend fun updateProject(project: ProjectEntity)
    
    @Delete
    suspend fun deleteProject(project: ProjectEntity)
    
    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProjectById(projectId: String)
    
    @Query("UPDATE projects SET message_count = :count, last_message_preview = :preview, last_message_date = :date, updated_date = :updated WHERE id = :projectId")
    suspend fun updateProjectStats(projectId: String, count: Int, preview: String, date: Long, updated: Long)
    
    @Query("UPDATE projects SET document_count = :count WHERE id = :projectId")
    suspend fun updateDocumentCount(projectId: String, count: Int)
    
    @Query("SELECT COUNT(*) FROM projects")
    suspend fun getTotalProjectCount(): Int
}