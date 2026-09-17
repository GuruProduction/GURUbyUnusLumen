package com.unuslumen.app.data.tools

import com.unuslumen.app.data.nowMillis
import com.unuslumen.app.domain.model.DocumentType
import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.model.ProjectDocument
import com.unuslumen.app.domain.model.ProjectFact
import com.unuslumen.app.domain.model.ProjectMessage
import com.unuslumen.app.domain.repository.ProjectRepository
import com.unuslumen.app.domain.use_case.CreateProjectUseCase
import com.unuslumen.app.domain.use_case.DeleteProjectUseCase
import com.unuslumen.app.domain.use_case.GetAllProjectsUseCase
import com.unuslumen.app.domain.use_case.GetProjectUseCase
import com.unuslumen.app.domain.use_case.SearchProjectsUseCase
import com.unuslumen.app.domain.use_case.UpdateProjectUseCase
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class ProjectToolExecutor(
    private val createProjectUseCase: CreateProjectUseCase,
    private val searchProjectsUseCase: SearchProjectsUseCase,
    private val updateProjectUseCase: UpdateProjectUseCase,
    private val deleteProjectUseCase: DeleteProjectUseCase,
    private val getAllProjectsUseCase: GetAllProjectsUseCase,
    private val getProjectUseCase: GetProjectUseCase,
    private val projectRepository: ProjectRepository
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ProjectToolDefinitions.CREATE_PROJECT -> createProject(args)
        ProjectToolDefinitions.SEARCH_PROJECTS -> searchProjects(args)
        ProjectToolDefinitions.GET_ALL_PROJECTS -> getAllProjects()
        ProjectToolDefinitions.GET_PROJECT -> getProject(args)
        ProjectToolDefinitions.UPDATE_PROJECT -> updateProject(args)
        ProjectToolDefinitions.DELETE_PROJECT -> deleteProject(args)
        ProjectToolDefinitions.ADD_PROJECT_MESSAGE -> addProjectMessage(args)
        ProjectToolDefinitions.GET_PROJECT_MESSAGES -> getProjectMessages(args)
        ProjectToolDefinitions.SEARCH_PROJECT_MESSAGES -> searchProjectMessages(args)
        ProjectToolDefinitions.ADD_PROJECT_DOCUMENT -> addProjectDocument(args)
        ProjectToolDefinitions.GET_PROJECT_DOCUMENTS -> getProjectDocuments(args)
        ProjectToolDefinitions.SEARCH_PROJECT_DOCUMENTS -> searchProjectDocuments(args)
        ProjectToolDefinitions.UPDATE_PROJECT_DOCUMENT -> updateProjectDocument(args)
        ProjectToolDefinitions.DELETE_PROJECT_DOCUMENT -> deleteProjectDocument(args)
        ProjectToolDefinitions.ADD_PROJECT_FACT -> addProjectFact(args)
        ProjectToolDefinitions.GET_PROJECT_FACTS -> getProjectFacts(args)
        ProjectToolDefinitions.SEARCH_PROJECT_FACTS -> searchProjectFacts(args)
        ProjectToolDefinitions.DELETE_PROJECT_FACT -> deleteProjectFact(args)
        ProjectToolDefinitions.GET_PROJECT_SUMMARIES -> getProjectSummaries()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createProject(args: Map<String, Any?>): ToolExecutionResult {
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val description = args["description"] as? String ?: ""
        val promptOverlay = args["promptOverlay"] as? String ?: ""
        val color = args["color"] as? String ?: "#6366f1"
        val icon = args["icon"] as? String ?: "folder"
        val id = Uuid.random().toString(); val now = nowMillis()
        val project = Project(id = id, title = title, description = description, promptOverlay = promptOverlay, createdDate = now, updatedDate = now, color = color, icon = icon, isActive = true)
        createProjectUseCase(project)
        val r = ProjectIdResult(createdProjectId = id, title = title)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectIdResult.serializer(), r))
    }

    private suspend fun searchProjects(args: Map<String, Any?>): ToolExecutionResult {
        val query = args["query"] as? String ?: ""
        val r = SearchProjectsResult(searchProjectsUseCase(query))
        return ToolExecutionResult.success(r, json.encodeToString(SearchProjectsResult.serializer(), r))
    }

    private suspend fun getAllProjects(): ToolExecutionResult {
        val r = SearchProjectsResult(getAllProjectsUseCase().first())
        return ToolExecutionResult.success(r, json.encodeToString(SearchProjectsResult.serializer(), r))
    }

    private suspend fun getProject(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val project = getProjectUseCase(id) ?: return ToolExecutionResult.error("No project found with ID: '$id'.")
        val r = ProjectResult(project)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectResult.serializer(), r))
    }

    private suspend fun updateProject(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val title = args["title"] as? String
        val description = args["description"] as? String
        val promptOverlay = args["promptOverlay"] as? String
        val color = args["color"] as? String
        val icon = args["icon"] as? String
        val isActive = args["isActive"] as? Boolean
        val existing = getProjectUseCase(id) ?: return ToolExecutionResult.error("No project found with ID: '$id'.")
        val updated = existing.copy(title = title ?: existing.title, description = description ?: existing.description, promptOverlay = promptOverlay ?: existing.promptOverlay, color = color ?: existing.color, icon = icon ?: existing.icon, isActive = isActive ?: existing.isActive, updatedDate = nowMillis())
        updateProjectUseCase(updated)
        val r = ProjectResult(updated)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectResult.serializer(), r))
    }

    private suspend fun deleteProject(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val project = getProjectUseCase(id) ?: return ToolExecutionResult.error("No project found with ID: '$id'.")
        deleteProjectUseCase(id)
        val r = DeleteProjectResult(deletedProjectId = id, title = project.title)
        return ToolExecutionResult.success(r, json.encodeToString(DeleteProjectResult.serializer(), r))
    }

    private suspend fun addProjectMessage(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val role = args["role"] as? String ?: return ToolExecutionResult.error("Missing 'role'")
        val content = args["content"] as? String ?: return ToolExecutionResult.error("Missing 'content'")
        val toolCalls = args["toolCalls"] as? String ?: ""
        val toolResults = args["toolResults"] as? String ?: ""
        val id = Uuid.random().toString()
        val message = ProjectMessage(id = id, projectId = projectId, role = role, content = content, timestamp = nowMillis(), toolCalls = toolCalls, toolResults = toolResults)
        projectRepository.addMessage(message)
        val r = ProjectMessageIdResult(createdMessageId = id)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectMessageIdResult.serializer(), r))
    }

    private suspend fun getProjectMessages(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val limit = (args["limit"] as? Number)?.toInt() ?: 50
        val r = ProjectMessagesResult(projectRepository.getRecentMessages(projectId, limit))
        return ToolExecutionResult.success(r, json.encodeToString(ProjectMessagesResult.serializer(), r))
    }

    private suspend fun searchProjectMessages(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val query = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'")
        val messages = projectRepository.getMessages(projectId)
        val r = ProjectMessagesResult(messages.filter { it.content.contains(query, ignoreCase = true) })
        return ToolExecutionResult.success(r, json.encodeToString(ProjectMessagesResult.serializer(), r))
    }

    private suspend fun addProjectDocument(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val content = args["content"] as? String ?: return ToolExecutionResult.error("Missing 'content'")
        val type = args["type"] as? String ?: "TEXT"
        val sourceUri = args["sourceUri"] as? String ?: ""
        val id = Uuid.random().toString(); val now = nowMillis()
        val document = ProjectDocument(id = id, projectId = projectId, title = title, content = content, type = DocumentType.valueOf(type.uppercase()), createdDate = now, updatedDate = now, sourceUri = sourceUri, size = content.length.toLong())
        projectRepository.addDocument(document)
        val r = ProjectDocumentIdResult(createdDocumentId = id)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectDocumentIdResult.serializer(), r))
    }

    private suspend fun getProjectDocuments(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val r = ProjectDocumentsResult(projectRepository.getDocuments(projectId))
        return ToolExecutionResult.success(r, json.encodeToString(ProjectDocumentsResult.serializer(), r))
    }

    private suspend fun searchProjectDocuments(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val query = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'")
        val r = ProjectDocumentsResult(projectRepository.searchDocuments(projectId, query))
        return ToolExecutionResult.success(r, json.encodeToString(ProjectDocumentsResult.serializer(), r))
    }

    private suspend fun updateProjectDocument(args: Map<String, Any?>): ToolExecutionResult {
        val documentId = args["documentId"] as? String ?: return ToolExecutionResult.error("Missing 'documentId'")
        val title = args["title"] as? String
        val content = args["content"] as? String
        val existing = projectRepository.getDocument(documentId) ?: return ToolExecutionResult.error("No document found with ID: '$documentId'.")
        val updated = existing.copy(title = title ?: existing.title, content = content ?: existing.content, updatedDate = nowMillis(), size = (content ?: existing.content).length.toLong())
        projectRepository.updateDocument(updated)
        val r = ProjectDocumentResult(updated)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectDocumentResult.serializer(), r))
    }

    private suspend fun deleteProjectDocument(args: Map<String, Any?>): ToolExecutionResult {
        val documentId = args["documentId"] as? String ?: return ToolExecutionResult.error("Missing 'documentId'")
        val document = projectRepository.getDocument(documentId) ?: return ToolExecutionResult.error("No document found with ID: '$documentId'.")
        projectRepository.deleteDocument(documentId)
        val r = DeleteProjectDocumentResult(deletedDocumentId = documentId, title = document.title)
        return ToolExecutionResult.success(r, json.encodeToString(DeleteProjectDocumentResult.serializer(), r))
    }

    private suspend fun addProjectFact(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val category = args["category"] as? String ?: return ToolExecutionResult.error("Missing 'category'")
        val fact = args["fact"] as? String ?: return ToolExecutionResult.error("Missing 'fact'")
        val confidence = (args["confidence"] as? Number)?.toFloat() ?: 1.0f
        val id = Uuid.random().toString()
        val projectFact = ProjectFact(id = id, projectId = projectId, category = category, fact = fact, confidence = confidence, extractedDate = nowMillis())
        projectRepository.addFact(projectFact)
        val r = ProjectFactIdResult(createdFactId = id)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectFactIdResult.serializer(), r))
    }

    private suspend fun getProjectFacts(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val category = args["category"] as? String
        val facts = if (category != null) projectRepository.getFactsByCategory(projectId, category) else projectRepository.getFacts(projectId)
        val r = ProjectFactsResult(facts)
        return ToolExecutionResult.success(r, json.encodeToString(ProjectFactsResult.serializer(), r))
    }

    private suspend fun searchProjectFacts(args: Map<String, Any?>): ToolExecutionResult {
        val projectId = args["projectId"] as? String ?: return ToolExecutionResult.error("Missing 'projectId'")
        val query = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'")
        val facts = projectRepository.getFacts(projectId)
        val r = ProjectFactsResult(facts.filter { it.fact.contains(query, ignoreCase = true) })
        return ToolExecutionResult.success(r, json.encodeToString(ProjectFactsResult.serializer(), r))
    }

    private suspend fun deleteProjectFact(args: Map<String, Any?>): ToolExecutionResult {
        val factId = args["factId"] as? String ?: return ToolExecutionResult.error("Missing 'factId'")
        projectRepository.deleteFact(factId)
        val r = DeleteProjectFactResult(deletedFactId = factId)
        return ToolExecutionResult.success(r, json.encodeToString(DeleteProjectFactResult.serializer(), r))
    }

    private suspend fun getProjectSummaries(): ToolExecutionResult {
        val r = ProjectSummariesResult(projectRepository.getAllProjectSummaries())
        return ToolExecutionResult.success(r, json.encodeToString(ProjectSummariesResult.serializer(), r))
    }
}