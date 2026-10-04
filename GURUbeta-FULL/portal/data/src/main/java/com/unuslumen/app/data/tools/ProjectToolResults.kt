// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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