package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.ProjectDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDocumentDao {
    
    @Query("SELECT * FROM project_documents WHERE project_id = :projectId ORDER BY updated_date DESC")
    suspend fun getDocuments(projectId: String): List<ProjectDocumentEntity>
    
    @Query("SELECT * FROM project_documents WHERE id = :documentId")
    suspend fun getDocument(documentId: String): ProjectDocumentEntity?
    
    @Query("SELECT * FROM project_documents WHERE project_id = :projectId AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%')")
    suspend fun searchDocuments(projectId: String, query: String): List<ProjectDocumentEntity>
    
    @Query("SELECT * FROM project_documents WHERE project_id = :projectId AND (embedding IS NULL OR embedding = '[]' OR embedding = '')")
    suspend fun getDocumentsNeedingEmbeddings(projectId: String): List<ProjectDocumentEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: ProjectDocumentEntity): Long
    
    @Update
    suspend fun updateDocument(document: ProjectDocumentEntity)
    
    @Delete
    suspend fun deleteDocument(document: ProjectDocumentEntity)
    
    @Query("DELETE FROM project_documents WHERE id = :documentId")
    suspend fun deleteDocumentById(documentId: String)
    
    @Query("DELETE FROM project_documents WHERE project_id = :projectId")
    suspend fun deleteDocumentsByProject(projectId: String)
    
    @Query("UPDATE project_documents SET embedding = :embedding WHERE id = :documentId")
    suspend fun updateEmbedding(documentId: String, embedding: String)
    
    @Query("SELECT COUNT(*) FROM project_documents WHERE project_id = :projectId")
    suspend fun getDocumentCount(projectId: String): Int

    @SkipQueryVerification
    @Query("""
        SELECT d.* FROM project_documents d
        WHERE d.rowid IN (SELECT rowid FROM project_documents_fts WHERE project_documents_fts MATCH :query)
        AND d.project_id = :projectId
        ORDER BY d.updated_date DESC
    """)
    suspend fun searchDocumentsFts(projectId: String, query: String): List<ProjectDocumentEntity>
}