package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.ProjectMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectMessageDao {
    
    @Query("SELECT * FROM project_messages WHERE project_id = :projectId ORDER BY timestamp ASC")
    suspend fun getMessages(projectId: String): List<ProjectMessageEntity>
    
    @Query("SELECT * FROM project_messages WHERE project_id = :projectId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(projectId: String, limit: Int): List<ProjectMessageEntity>
    
    @Query("SELECT * FROM project_messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): ProjectMessageEntity?
    
    @Query("SELECT * FROM project_messages WHERE project_id = :projectId AND (embedding IS NULL OR embedding = '[]' OR embedding = '')")
    suspend fun getMessagesNeedingEmbeddings(projectId: String): List<ProjectMessageEntity>
    
    @Query("SELECT * FROM project_messages WHERE project_id = :projectId AND content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(projectId: String, query: String): List<ProjectMessageEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ProjectMessageEntity): Long
    
    @Upsert
    suspend fun upsertMessages(messages: List<ProjectMessageEntity>)
    
    @Update
    suspend fun updateMessage(message: ProjectMessageEntity)
    
    @Delete
    suspend fun deleteMessage(message: ProjectMessageEntity)
    
    @Query("DELETE FROM project_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)
    
    @Query("DELETE FROM project_messages WHERE project_id = :projectId")
    suspend fun deleteMessagesByProject(projectId: String)
    
    @Query("UPDATE project_messages SET embedding = :embedding WHERE id = :messageId")
    suspend fun updateEmbedding(messageId: String, embedding: String)
    
    @Query("SELECT COUNT(*) FROM project_messages WHERE project_id = :projectId")
    suspend fun getMessageCount(projectId: String): Int

    @SkipQueryVerification
    @Query("""
        SELECT m.* FROM project_messages m
        WHERE m.rowid IN (SELECT rowid FROM project_messages_fts WHERE project_messages_fts MATCH :query)
        AND m.project_id = :projectId
        ORDER BY m.timestamp DESC
    """)
    suspend fun searchMessagesFts(projectId: String, query: String): List<ProjectMessageEntity>
}