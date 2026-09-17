package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.model.ProjectDocument
import com.unuslumen.app.domain.model.ProjectFact
import com.unuslumen.app.domain.model.ProjectMessage
import com.unuslumen.app.domain.model.ProjectSummary
import kotlinx.serialization.Serializable

@Serializable data class ProjectIdResult(val createdProjectId: String, val title: String) : ToolResultData
@Serializable data class SearchProjectsResult(val projects: List<Project>) : ToolResultData
@Serializable data class ProjectResult(val project: Project) : ToolResultData
@Serializable data class DeleteProjectResult(val deletedProjectId: String, val title: String) : ToolResultData
@Serializable data class ProjectMessageIdResult(val createdMessageId: String) : ToolResultData
@Serializable data class ProjectMessagesResult(val messages: List<ProjectMessage>) : ToolResultData
@Serializable data class ProjectDocumentIdResult(val createdDocumentId: String) : ToolResultData
@Serializable data class ProjectDocumentsResult(val documents: List<ProjectDocument>) : ToolResultData
@Serializable data class ProjectDocumentResult(val document: ProjectDocument) : ToolResultData
@Serializable data class DeleteProjectDocumentResult(val deletedDocumentId: String, val title: String) : ToolResultData
@Serializable data class ProjectFactIdResult(val createdFactId: String) : ToolResultData
@Serializable data class ProjectFactsResult(val facts: List<ProjectFact>) : ToolResultData
@Serializable data class DeleteProjectFactResult(val deletedFactId: String) : ToolResultData
@Serializable data class ProjectSummariesResult(val summaries: List<ProjectSummary>) : ToolResultData