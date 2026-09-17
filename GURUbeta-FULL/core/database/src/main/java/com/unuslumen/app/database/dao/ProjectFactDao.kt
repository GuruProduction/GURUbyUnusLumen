package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.ProjectFactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectFactDao {
    
    @Query("SELECT * FROM project_facts WHERE project_id = :projectId ORDER BY extracted_date DESC")
    suspend fun getFacts(projectId: String): List<ProjectFactEntity>
    
    @Query("SELECT * FROM project_facts WHERE project_id = :projectId AND category = :category ORDER BY extracted_date DESC")
    suspend fun getFactsByCategory(projectId: String, category: String): List<ProjectFactEntity>
    
    @Query("SELECT * FROM project_facts WHERE id = :factId")
    suspend fun getFact(factId: String): ProjectFactEntity?
    
    @Query("SELECT * FROM project_facts WHERE project_id = :projectId AND fact LIKE '%' || :query || '%'")
    suspend fun searchFacts(projectId: String, query: String): List<ProjectFactEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: ProjectFactEntity): Long
    
    @Upsert
    suspend fun upsertFacts(facts: List<ProjectFactEntity>)
    
    @Update
    suspend fun updateFact(fact: ProjectFactEntity)
    
    @Delete
    suspend fun deleteFact(fact: ProjectFactEntity)
    
    @Query("DELETE FROM project_facts WHERE id = :factId")
    suspend fun deleteFactById(factId: String)
    
    @Query("DELETE FROM project_facts WHERE project_id = :projectId")
    suspend fun deleteFactsByProject(projectId: String)
    
    @Query("UPDATE project_facts SET last_recalled_date = :timestamp WHERE id = :factId")
    suspend fun markFactRecalled(factId: String, timestamp: Long)
    
    @Query("SELECT COUNT(*) FROM project_facts WHERE project_id = :projectId")
    suspend fun getFactCount(projectId: String): Int

    @SkipQueryVerification
    @Query("""
        SELECT f.* FROM project_facts f
        WHERE f.rowid IN (SELECT rowid FROM project_facts_fts WHERE project_facts_fts MATCH :query)
        AND f.project_id = :projectId
        ORDER BY f.extracted_date DESC
    """)
    suspend fun searchFactsFts(projectId: String, query: String): List<ProjectFactEntity>
}