package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ProjectToolDefinitions : ToolSetRegistration {
    const val CREATE_PROJECT = "create_project"
    const val SEARCH_PROJECTS = "search_projects"
    const val GET_ALL_PROJECTS = "get_all_projects"
    const val GET_PROJECT = "get_project"
    const val UPDATE_PROJECT = "update_project"
    const val DELETE_PROJECT = "delete_project"
    const val ADD_PROJECT_MESSAGE = "add_project_message"
    const val GET_PROJECT_MESSAGES = "get_project_messages"
    const val SEARCH_PROJECT_MESSAGES = "search_project_messages"
    const val ADD_PROJECT_DOCUMENT = "add_project_document"
    const val GET_PROJECT_DOCUMENTS = "get_project_documents"
    const val SEARCH_PROJECT_DOCUMENTS = "search_project_documents"
    const val UPDATE_PROJECT_DOCUMENT = "update_project_document"
    const val DELETE_PROJECT_DOCUMENT = "delete_project_document"
    const val ADD_PROJECT_FACT = "add_project_fact"
    const val GET_PROJECT_FACTS = "get_project_facts"
    const val SEARCH_PROJECT_FACTS = "search_project_facts"
    const val DELETE_PROJECT_FACT = "delete_project_fact"
    const val GET_PROJECT_SUMMARIES = "get_project_summaries"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_PROJECT, description = "Create a new project workspace. Projects are sandboxed AI workspaces for specific tasks like app development, wedding planning, crypto trading, etc. Each project has its own conversation history, documents, and context.", category = "project", parameters = listOf(ToolParameter("title", ToolParameterType.String, true, "Project title (e.g., 'Wedding Planning', 'Crypto Trading Bot', 'Novel Writing')"), ToolParameter("description", ToolParameterType.String, false, "Project description (what this project is about)"), ToolParameter("promptOverlay", ToolParameterType.String, false, "Custom instructions for the project's AI agent (prompt overlay)"), ToolParameter("color", ToolParameterType.String, false, "Accent color for UI (hex, e.g., '#6366f1')"), ToolParameter("icon", ToolParameterType.String, false, "Icon identifier (e.g., 'folder', 'code', 'book', 'chart')")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_PROJECTS, description = "Search projects by title or description (partial match).", category = "project", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_PROJECTS, description = "Get all projects. Returns a list of all project workspaces.", category = "project", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_PROJECT, description = "Get a project by its ID. Returns full project details.", category = "project", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "The project ID")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_PROJECT, description = "Update an existing project. Only the fields you provide will be changed.", category = "project", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "The ID of the project to update"), ToolParameter("title", ToolParameterType.String, false, "New title (null to keep current)"), ToolParameter("description", ToolParameterType.String, false, "New description (null to keep current)"), ToolParameter("promptOverlay", ToolParameterType.String, false, "New prompt overlay/instructions (null to keep current)"), ToolParameter("color", ToolParameterType.String, false, "New accent color (null to keep current)"), ToolParameter("icon", ToolParameterType.String, false, "New icon identifier (null to keep current)"), ToolParameter("isActive", ToolParameterType.Boolean, false, "Whether project is active (null to keep current)")), permissions = emptyList()),
        ToolDefinition(name = DELETE_PROJECT, description = "Delete a project permanently. This will also delete all messages, documents, and facts associated with the project. This cannot be undone.", category = "project", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "The ID of the project to delete")), permissions = emptyList()),
        ToolDefinition(name = ADD_PROJECT_MESSAGE, description = "Add a message to a project's conversation history. Used to store context for the project's AI agent.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("role", ToolParameterType.String, true, "Message role: 'user' or 'assistant'"), ToolParameter("content", ToolParameterType.String, true, "Message content"), ToolParameter("toolCalls", ToolParameterType.String, false, "Tool calls JSON (if any)"), ToolParameter("toolResults", ToolParameterType.String, false, "Tool results JSON (if any)")), permissions = emptyList()),
        ToolDefinition(name = GET_PROJECT_MESSAGES, description = "Get recent messages from a project's conversation history.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum number of messages to return (default 50)")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_PROJECT_MESSAGES, description = "Search messages in a project's conversation history by content.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = ADD_PROJECT_DOCUMENT, description = "Add a document to a project. Documents provide context for the project's AI agent.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("title", ToolParameterType.String, true, "Document title"), ToolParameter("content", ToolParameterType.String, true, "Document content"), ToolParameter("type", ToolParameterType.String, false, "Document type: TEXT, MARKDOWN, CODE, JSON, CSV, URL, IMAGE, PDF, OTHER"), ToolParameter("sourceUri", ToolParameterType.String, false, "Original file URI if imported")), permissions = emptyList()),
        ToolDefinition(name = GET_PROJECT_DOCUMENTS, description = "Get all documents for a project.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_PROJECT_DOCUMENTS, description = "Search documents in a project by title or content.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_PROJECT_DOCUMENT, description = "Update an existing project document.", category = "project", parameters = listOf(ToolParameter("documentId", ToolParameterType.String, true, "The document ID"), ToolParameter("title", ToolParameterType.String, false, "New title (null to keep current)"), ToolParameter("content", ToolParameterType.String, false, "New content (null to keep current)")), permissions = emptyList()),
        ToolDefinition(name = DELETE_PROJECT_DOCUMENT, description = "Delete a document from a project.", category = "project", parameters = listOf(ToolParameter("documentId", ToolParameterType.String, true, "The document ID")), permissions = emptyList()),
        ToolDefinition(name = ADD_PROJECT_FACT, description = "Add a fact to a project's knowledge base. Facts are extracted from conversations and provide context for the AI agent.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("category", ToolParameterType.String, true, "Fact category (e.g., 'preference', 'decision', 'requirement')"), ToolParameter("fact", ToolParameterType.String, true, "The fact content"), ToolParameter("confidence", ToolParameterType.Float, false, "Confidence level (0.0 to 1.0)")), permissions = emptyList()),
        ToolDefinition(name = GET_PROJECT_FACTS, description = "Get all facts for a project, optionally filtered by category.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("category", ToolParameterType.String, false, "Filter by category (optional)")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_PROJECT_FACTS, description = "Search facts in a project by content.", category = "project", parameters = listOf(ToolParameter("projectId", ToolParameterType.String, true, "The project ID"), ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = DELETE_PROJECT_FACT, description = "Delete a fact from a project's knowledge base.", category = "project", parameters = listOf(ToolParameter("factId", ToolParameterType.String, true, "The fact ID")), permissions = emptyList()),
        ToolDefinition(name = GET_PROJECT_SUMMARIES, description = "Get summaries of all projects. This is for the Master Guru to understand what projects exist and their status.", category = "project", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = ProjectToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}