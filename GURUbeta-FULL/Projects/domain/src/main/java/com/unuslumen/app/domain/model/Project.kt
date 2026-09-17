package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * A Project is a sandboxed workspace for AI-assisted work.
 * Each project has its own conversation history, documents, and context
 * that is isolated from other projects and from the main Guru instance.
 *
 * Projects can be anything: app development, wedding planning, 
 * crypto trading, novel writing, meal planning, etc.
 */
@Serializable
data class Project(
    val id: String,
    val title: String,
    val description: String = "",
    val promptOverlay: String = "", // Custom instructions for this project's agent
    val createdDate: Long = System.currentTimeMillis(),
    val updatedDate: Long = System.currentTimeMillis(),
    val color: String = "#6366f1", // Accent color for UI
    val icon: String = "folder", // Icon identifier
    val isActive: Boolean = true,
    val messageCount: Int = 0,
    val documentCount: Int = 0,
    val lastMessagePreview: String = "",
    val lastMessageDate: Long = 0L
)

/**
 * A message within a project's conversation.
 * Completely sandboxed - only visible within this project.
 */
@Serializable
data class ProjectMessage(
    val id: String,
    val projectId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val embedding: List<Float> = emptyList(),
    val toolCalls: String = "",
    val toolResults: String = ""
)

/**
 * A document attached to a project.
 * Documents provide context for the project's agent.
 */
@Serializable
data class ProjectDocument(
    val id: String,
    val projectId: String,
    val title: String,
    val content: String,
    val type: DocumentType = DocumentType.TEXT,
    val createdDate: Long = System.currentTimeMillis(),
    val updatedDate: Long = System.currentTimeMillis(),
    val embedding: List<Float> = emptyList(),
    val sourceUri: String = "", // Original file URI if imported
    val size: Long = 0L // Size in bytes
)

@Serializable
enum class DocumentType {
    TEXT,       // Plain text notes
    MARKDOWN,   // Markdown documents
    CODE,       // Code files
    JSON,       // JSON data
    CSV,        // CSV data
    URL,        // Web links/references
    IMAGE,      // Images (stored as base64 or URI)
    PDF,        // PDF documents (extracted text)
    OTHER      // Other file types
}

/**
 * A fact extracted from project conversations.
 * Like MemoryFact but sandboxed to a project.
 */
@Serializable
data class ProjectFact(
    val id: String,
    val projectId: String,
    val category: String,
    val fact: String,
    val embedding: List<Float> = emptyList(),
    val confidence: Float = 1.0f,
    val sourceMessageIds: List<String> = emptyList(),
    val extractedDate: Long = System.currentTimeMillis(),
    val lastRecalledDate: Long = 0L
)

/**
 * Project summary for list views.
 */
@Serializable
data class ProjectSummary(
    val id: String,
    val title: String,
    val description: String,
    val color: String,
    val icon: String,
    val messageCount: Int,
    val documentCount: Int,
    val lastMessagePreview: String,
    val lastMessageDate: Long,
    val isActive: Boolean
)

/**
 * Retrieved context for a project agent.
 * Contains relevant messages, documents, and facts.
 */
data class ProjectContext(
    val messages: List<ProjectMessage>,
    val documents: List<ProjectDocument>,
    val facts: List<ProjectFact>,
    val preamble: String
)