// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

/**
 * Configuration for a project-specific Guru agent.
 * Each project can have its own agent with custom settings.
 */
data class ProjectAgentConfig(
    val projectId: String,
    val systemPromptOverlay: String = "", // Additional instructions specific to this project
    val temperature: Float = 1.25f,
    val maxTokens: Int = 4096,
    val enableTools: Boolean = true,
    val allowedToolCategories: List<String> = emptyList(), // Empty = all tools allowed
    val blockedToolCategories: List<String> = emptyList(),
    val autoSaveMessages: Boolean = true,
    val extractFacts: Boolean = true,
    val contextWindowMessages: Int = 20,
    val contextWindowDocuments: Int = 5,
    val contextWindowFacts: Int = 10
)

/**
 * Result of a project agent message.
 */
sealed class ProjectAgentResult {
    data class Success(val message: AiMessage) : ProjectAgentResult()
    data class Error(val message: String, val code: String? = null) : ProjectAgentResult()
    data class ToolCall(val toolName: String, val result: String) : ProjectAgentResult()
}

/**
 * Interface for project-specific AI agents.
 * Each project agent is sandboxed to its project context.
 */
interface ProjectAgentRepository {
    /**
     * Send a message to a project's agent.
     * The agent will use the project's context (messages, documents, facts).
     *
     * [conversationId] lets the durable run hang off the project conversation it belongs
     * to, so the execution history is reachable from the place the work was asked for.
     * It is nullable and defaulted because a project agent is a different feature from the
     * spawn path and existing callers predate the run ledger; a caller that supplies it
     * gets an execution history attached to its conversation, and a caller that does not
     * still gets a run under the project id rather than no record at all.
     */
    suspend fun sendMessage(
        projectId: String,
        messages: List<AiMessage>,
        config: ProjectAgentConfig? = null,
        conversationId: String? = null,
    ): kotlinx.coroutines.flow.Flow<AiMessage>
    
    /**
     * Get the current configuration for a project's agent.
     */
    suspend fun getAgentConfig(projectId: String): ProjectAgentConfig?
    
    /**
     * Update the configuration for a project's agent.
     */
    suspend fun updateAgentConfig(config: ProjectAgentConfig)
    
    /**
     * Clear the conversation history for a project's agent.
     */
    suspend fun clearConversation(projectId: String)
    
    /**
     * Get available tools for a project agent.
     * Respects allowedToolCategories and blockedToolCategories.
     */
    suspend fun getAvailableTools(projectId: String): List<String>
}