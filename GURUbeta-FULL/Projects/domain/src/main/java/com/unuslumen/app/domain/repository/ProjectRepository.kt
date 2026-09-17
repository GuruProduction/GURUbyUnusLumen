package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.model.ProjectContext
import com.unuslumen.app.domain.model.ProjectDocument
import com.unuslumen.app.domain.model.ProjectFact
import com.unuslumen.app.domain.model.ProjectMessage
import com.unuslumen.app.domain.model.ProjectSummary
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing Projects - sandboxed AI workspaces.
 * 
 * Each project is completely isolated:
 * - Own conversation history (ProjectMessage)
 * - Own documents (ProjectDocument)
 * - Own extracted facts (ProjectFact)
 * - Own agent context
 * 
 * The Master Guru can query across projects for summaries,
 * but project agents are sandboxed to their own project.
 */
interface ProjectRepository {
    
    // ==================== Project CRUD ====================
    
    suspend fun createProject(project: Project): String
    suspend fun updateProject(project: Project)
    suspend fun deleteProject(projectId: String)
    suspend fun getProject(projectId: String): Project?
    fun getAllProjects(): Flow<List<Project>>
    fun getActiveProjects(): Flow<List<Project>>
    suspend fun searchProjects(query: String): List<Project>
    
    // ==================== Project Messages ====================
    
    suspend fun addMessage(message: ProjectMessage): String
    suspend fun addMessages(messages: List<ProjectMessage>)
    suspend fun getMessages(projectId: String): List<ProjectMessage>
    suspend fun getRecentMessages(projectId: String, limit: Int = 50): List<ProjectMessage>
    suspend fun deleteMessage(messageId: String)
    suspend fun updateMessageEmbedding(messageId: String, embedding: List<Float>)
    suspend fun getMessagesNeedingEmbeddings(projectId: String): List<ProjectMessage>
    
    // ==================== Project Documents ====================
    
    suspend fun addDocument(document: ProjectDocument): String
    suspend fun updateDocument(document: ProjectDocument)
    suspend fun deleteDocument(documentId: String)
    suspend fun getDocument(documentId: String): ProjectDocument?
    suspend fun getDocuments(projectId: String): List<ProjectDocument>
    suspend fun searchDocuments(projectId: String, query: String): List<ProjectDocument>
    suspend fun updateDocumentEmbedding(documentId: String, embedding: List<Float>)
    suspend fun getDocumentsNeedingEmbeddings(projectId: String): List<ProjectDocument>
    
    // ==================== Project Facts ====================
    
    suspend fun addFact(fact: ProjectFact): String
    suspend fun addFacts(facts: List<ProjectFact>)
    suspend fun getFacts(projectId: String): List<ProjectFact>
    suspend fun getFactsByCategory(projectId: String, category: String): List<ProjectFact>
    suspend fun deleteFact(factId: String)
    suspend fun markFactRecalled(factId: String)
    
    // ==================== Context Building ====================
    
    /**
     * Build context for a project agent.
     * Retrieves relevant messages, documents, and facts based on the query.
     */
    suspend fun buildProjectContext(
        projectId: String,
        query: String,
        maxMessages: Int = 20,
        maxDocuments: Int = 5,
        maxFacts: Int = 10
    ): ProjectContext
    
    // ==================== Summaries (for Master Guru) ====================
    
    /**
     * Get summaries of all projects for the Master Guru.
     * Used when Master Guru needs to know what projects exist.
     */
    suspend fun getAllProjectSummaries(): List<ProjectSummary>
    
    /**
     * Get summary of a specific project.
     */
    suspend fun getProjectSummary(projectId: String): ProjectSummary?
    
    // ==================== Statistics ====================
    
    suspend fun getProjectMessageCount(projectId: String): Int
    suspend fun getProjectDocumentCount(projectId: String): Int
    suspend fun getTotalProjectCount(): Int
}