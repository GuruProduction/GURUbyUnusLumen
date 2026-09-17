package com.unuslumen.app.data

import com.unuslumen.app.database.dao.ProjectDao
import com.unuslumen.app.database.dao.ProjectDocumentDao
import com.unuslumen.app.database.dao.ProjectFactDao
import com.unuslumen.app.database.dao.ProjectMessageDao
import com.unuslumen.app.database.entity.toProject
import com.unuslumen.app.database.entity.toProjectDocument
import com.unuslumen.app.database.entity.toProjectDocumentEntity
import com.unuslumen.app.database.entity.toProjectEntity
import com.unuslumen.app.database.entity.toProjectFact
import com.unuslumen.app.database.entity.toProjectFactEntity
import com.unuslumen.app.database.entity.toProjectMessage
import com.unuslumen.app.database.entity.toProjectMessageEntity
import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.model.ProjectContext
import com.unuslumen.app.domain.model.ProjectDocument
import com.unuslumen.app.domain.model.ProjectFact
import com.unuslumen.app.domain.model.ProjectMessage
import com.unuslumen.app.domain.model.ProjectSummary
import com.unuslumen.app.domain.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import kotlin.math.min
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [ProjectRepository::class])
class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val projectMessageDao: ProjectMessageDao,
    private val projectDocumentDao: ProjectDocumentDao,
    private val projectFactDao: ProjectFactDao,
    @Named("ioDispatcher") private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
) : ProjectRepository {

    // ==================== Project CRUD ====================

    override suspend fun createProject(project: Project): String = withContext(ioDispatcher) {
        val id = if (project.id.isBlank()) Uuid.random().toString() else project.id
        val now = System.currentTimeMillis()
        val newProject = project.copy(
            id = id,
            createdDate = project.createdDate.takeIf { it > 0 } ?: now,
            updatedDate = project.updatedDate.takeIf { it > 0 } ?: now
        )
        projectDao.insertProject(newProject.toProjectEntity())
        id
    }

    override suspend fun updateProject(project: Project) = withContext(ioDispatcher) {
        val updated = project.copy(updatedDate = System.currentTimeMillis())
        projectDao.updateProject(updated.toProjectEntity())
    }

    override suspend fun deleteProject(projectId: String) = withContext(ioDispatcher) {
        // Cascade delete will handle messages, documents, and facts
        projectDao.deleteProjectById(projectId)
    }

    override suspend fun getProject(projectId: String): Project? = withContext(ioDispatcher) {
        projectDao.getProject(projectId)?.toProject()
    }

    override fun getAllProjects(): Flow<List<Project>> {
        return projectDao.getAllProjects()
            .flowOn(ioDispatcher)
            .map { projects -> projects.map { it.toProject() } }
    }

    override fun getActiveProjects(): Flow<List<Project>> {
        return projectDao.getActiveProjects()
            .flowOn(ioDispatcher)
            .map { projects -> projects.map { it.toProject() } }
    }

    override suspend fun searchProjects(query: String): List<Project> = withContext(ioDispatcher) {
        projectDao.searchProjects(query).map { it.toProject() }
    }

    // ==================== Project Messages ====================

    override suspend fun addMessage(message: ProjectMessage): String = withContext(ioDispatcher) {
        val id = if (message.id.isBlank()) Uuid.random().toString() else message.id
        val now = System.currentTimeMillis()
        val newMessage = message.copy(
            id = id,
            timestamp = if (message.timestamp > 0) message.timestamp else now
        )
        projectMessageDao.insertMessage(newMessage.toProjectMessageEntity())
        
        // Update project stats
        val count = projectMessageDao.getMessageCount(message.projectId)
        val preview = message.content.take(100)
        projectDao.updateProjectStats(
            projectId = message.projectId,
            count = count,
            preview = preview,
            date = now,
            updated = now
        )
        
        id
    }

    override suspend fun addMessages(messages: List<ProjectMessage>) = withContext(ioDispatcher) {
        if (messages.isEmpty()) return@withContext
        
        messages.forEach { message ->
            addMessage(message)
        }
    }

    override suspend fun getMessages(projectId: String): List<ProjectMessage> = withContext(ioDispatcher) {
        projectMessageDao.getMessages(projectId).map { it.toProjectMessage() }
    }

    override suspend fun getRecentMessages(projectId: String, limit: Int): List<ProjectMessage> = withContext(ioDispatcher) {
        projectMessageDao.getRecentMessages(projectId, limit).map { it.toProjectMessage() }
    }

    override suspend fun deleteMessage(messageId: String) = withContext(ioDispatcher) {
        projectMessageDao.deleteMessageById(messageId)
    }

    override suspend fun updateMessageEmbedding(messageId: String, embedding: List<Float>) = withContext(ioDispatcher) {
        val embeddingString = embedding.joinToString(",")
        projectMessageDao.updateEmbedding(messageId, embeddingString)
    }

    override suspend fun getMessagesNeedingEmbeddings(projectId: String): List<ProjectMessage> = withContext(ioDispatcher) {
        projectMessageDao.getMessagesNeedingEmbeddings(projectId).map { it.toProjectMessage() }
    }

    // ==================== Project Documents ====================

    override suspend fun addDocument(document: ProjectDocument): String = withContext(ioDispatcher) {
        val id = if (document.id.isBlank()) Uuid.random().toString() else document.id
        val now = System.currentTimeMillis()
        val newDocument = document.copy(
            id = id,
            createdDate = if (document.createdDate > 0) document.createdDate else now,
            updatedDate = if (document.updatedDate > 0) document.updatedDate else now
        )
        projectDocumentDao.insertDocument(newDocument.toProjectDocumentEntity())
        
        // Update project document count
        val count = projectDocumentDao.getDocumentCount(document.projectId)
        projectDao.updateDocumentCount(document.projectId, count)
        
        id
    }

    override suspend fun updateDocument(document: ProjectDocument) = withContext(ioDispatcher) {
        val updated = document.copy(updatedDate = System.currentTimeMillis())
        projectDocumentDao.updateDocument(updated.toProjectDocumentEntity())
    }

    override suspend fun deleteDocument(documentId: String) = withContext(ioDispatcher) {
        val document = projectDocumentDao.getDocument(documentId)
        if (document != null) {
            projectDocumentDao.deleteDocumentById(documentId)
            // Update project document count
            val count = projectDocumentDao.getDocumentCount(document.projectId)
            projectDao.updateDocumentCount(document.projectId, count)
        }
    }

    override suspend fun getDocument(documentId: String): ProjectDocument? = withContext(ioDispatcher) {
        projectDocumentDao.getDocument(documentId)?.toProjectDocument()
    }

    override suspend fun getDocuments(projectId: String): List<ProjectDocument> = withContext(ioDispatcher) {
        projectDocumentDao.getDocuments(projectId).map { it.toProjectDocument() }
    }

    override suspend fun searchDocuments(projectId: String, query: String): List<ProjectDocument> = withContext(ioDispatcher) {
        projectDocumentDao.searchDocuments(projectId, query).map { it.toProjectDocument() }
    }

    override suspend fun updateDocumentEmbedding(documentId: String, embedding: List<Float>) = withContext(ioDispatcher) {
        val embeddingString = embedding.joinToString(",")
        projectDocumentDao.updateEmbedding(documentId, embeddingString)
    }

    override suspend fun getDocumentsNeedingEmbeddings(projectId: String): List<ProjectDocument> = withContext(ioDispatcher) {
        projectDocumentDao.getDocumentsNeedingEmbeddings(projectId).map { it.toProjectDocument() }
    }

    // ==================== Project Facts ====================

    override suspend fun addFact(fact: ProjectFact): String = withContext(ioDispatcher) {
        val id = if (fact.id.isBlank()) Uuid.random().toString() else fact.id
        val now = System.currentTimeMillis()
        val newFact = fact.copy(
            id = id,
            extractedDate = if (fact.extractedDate > 0) fact.extractedDate else now
        )
        projectFactDao.insertFact(newFact.toProjectFactEntity())
        id
    }

    override suspend fun addFacts(facts: List<ProjectFact>) = withContext(ioDispatcher) {
        if (facts.isEmpty()) return@withContext
        projectFactDao.upsertFacts(facts.map { fact ->
            val id = if (fact.id.isBlank()) Uuid.random().toString() else fact.id
            val now = System.currentTimeMillis()
            fact.copy(
                id = id,
                extractedDate = if (fact.extractedDate > 0) fact.extractedDate else now
            ).toProjectFactEntity()
        })
    }

    override suspend fun getFacts(projectId: String): List<ProjectFact> = withContext(ioDispatcher) {
        projectFactDao.getFacts(projectId).map { it.toProjectFact() }
    }

    override suspend fun getFactsByCategory(projectId: String, category: String): List<ProjectFact> = withContext(ioDispatcher) {
        projectFactDao.getFactsByCategory(projectId, category).map { it.toProjectFact() }
    }

    override suspend fun deleteFact(factId: String) = withContext(ioDispatcher) {
        projectFactDao.deleteFactById(factId)
    }

    override suspend fun markFactRecalled(factId: String) = withContext(ioDispatcher) {
        projectFactDao.markFactRecalled(factId, System.currentTimeMillis())
    }

    // ==================== Context Building ====================

    override suspend fun buildProjectContext(
        projectId: String,
        query: String,
        maxMessages: Int,
        maxDocuments: Int,
        maxFacts: Int
    ): ProjectContext = withContext(ioDispatcher) {
        // Get recent messages for context
        val messages = getRecentMessages(projectId, maxMessages)
        
        // Get documents (for now, just get all - later we can do semantic search)
        val documents = getDocuments(projectId).take(maxDocuments)
        
        // Get facts (for now, just get all - later we can do semantic search)
        val facts = getFacts(projectId).take(maxFacts)
        
        // Build preamble
        val project = getProject(projectId)
        val preamble = buildContextPreamble(project, messages, documents, facts)
        
        ProjectContext(
            messages = messages,
            documents = documents,
            facts = facts,
            preamble = preamble
        )
    }

    private fun buildContextPreamble(
        project: Project?,
        messages: List<ProjectMessage>,
        documents: List<ProjectDocument>,
        facts: List<ProjectFact>
    ): String {
        val sb = StringBuilder()
        
        if (project != null) {
            sb.appendLine("=== PROJECT CONTEXT ===")
            sb.appendLine("Project: ${project.title}")
            if (project.description.isNotBlank()) {
                sb.appendLine("Description: ${project.description}")
            }
            if (project.promptOverlay.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("=== PROJECT-SPECIFIC INSTRUCTIONS ===")
                sb.appendLine(project.promptOverlay)
            }
            sb.appendLine()
        }
        
        if (documents.isNotEmpty()) {
            sb.appendLine("=== PROJECT DOCUMENTS ===")
            documents.forEach { doc ->
                sb.appendLine("[${doc.title}] (${doc.type})")
                sb.appendLine(doc.content.take(2000))
                if (doc.content.length > 2000) sb.appendLine("... (truncated)")
                sb.appendLine()
            }
        }
        
        if (facts.isNotEmpty()) {
            sb.appendLine("=== PROJECT FACTS ===")
            facts.forEach { fact ->
                sb.appendLine("[${fact.category}] ${fact.fact}")
            }
            sb.appendLine()
        }
        
        return sb.toString()
    }

    // ==================== Summaries ====================

    override suspend fun getAllProjectSummaries(): List<ProjectSummary> = withContext(ioDispatcher) {
        projectDao.getAllProjects().first().map { entity ->
            ProjectSummary(
                id = entity.id,
                title = entity.title,
                description = entity.description,
                color = entity.color,
                icon = entity.icon,
                messageCount = entity.messageCount,
                documentCount = entity.documentCount,
                lastMessagePreview = entity.lastMessagePreview,
                lastMessageDate = entity.lastMessageDate,
                isActive = entity.isActive
            )
        }
    }

    override suspend fun getProjectSummary(projectId: String): ProjectSummary? = withContext(ioDispatcher) {
        projectDao.getProject(projectId)?.let { entity ->
            ProjectSummary(
                id = entity.id,
                title = entity.title,
                description = entity.description,
                color = entity.color,
                icon = entity.icon,
                messageCount = entity.messageCount,
                documentCount = entity.documentCount,
                lastMessagePreview = entity.lastMessagePreview,
                lastMessageDate = entity.lastMessageDate,
                isActive = entity.isActive
            )
        }
    }

    // ==================== Statistics ====================

    override suspend fun getProjectMessageCount(projectId: String): Int = withContext(ioDispatcher) {
        projectMessageDao.getMessageCount(projectId)
    }

    override suspend fun getProjectDocumentCount(projectId: String): Int = withContext(ioDispatcher) {
        projectDocumentDao.getDocumentCount(projectId)
    }

    override suspend fun getTotalProjectCount(): Int = withContext(ioDispatcher) {
        projectDao.getTotalProjectCount()
    }
}