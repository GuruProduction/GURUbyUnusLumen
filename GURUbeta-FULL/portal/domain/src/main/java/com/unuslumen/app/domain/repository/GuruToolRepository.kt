package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.DefineToolRequest
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.GuruToolsSummary
import com.unuslumen.app.domain.model.ToolExecutionResult
import com.unuslumen.app.domain.model.ToolImplementation
import com.unuslumen.app.domain.model.ToolStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing Guru-defined tools.
 * 
 * Guru can create new tools by composing existing operations.
 * Tools require user approval before becoming active.
 */
interface GuruToolRepository {

    // ==================== Tool Management ====================

    /**
     * Define a new tool. Starts in PENDING status.
     */
    suspend fun defineTool(request: DefineToolRequest): GuruDefinedTool

    /**
     * Get all tools.
     */
    suspend fun getAllTools(): List<GuruDefinedTool>

    /**
     * Get all tools as a Flow.
     */
    fun getAllToolsFlow(): Flow<List<GuruDefinedTool>>

    /**
     * Get tools by status.
     */
    suspend fun getToolsByStatus(status: ToolStatus): List<GuruDefinedTool>

    /**
     * Get approved (active) tools.
     */
    suspend fun getApprovedTools(): List<GuruDefinedTool>

    /**
     * Get approved tools as a Flow.
     */
    fun getApprovedToolsFlow(): Flow<List<GuruDefinedTool>>

    /**
     * Get pending tools (awaiting approval).
     */
    suspend fun getPendingTools(): List<GuruDefinedTool>

    /**
     * Get a tool by ID.
     */
    suspend fun getTool(id: String): GuruDefinedTool?

    /**
     * Get a tool by name.
     */
    suspend fun getToolByName(name: String): GuruDefinedTool?

    /**
     * Approve a pending tool.
     */
    suspend fun approveTool(id: String): GuruDefinedTool

    /**
     * Disable an approved tool.
     */
    suspend fun disableTool(id: String): GuruDefinedTool

    /**
     * Re-enable a disabled tool.
     */
    suspend fun enableTool(id: String): GuruDefinedTool

    /**
     * Delete a tool.
     */
    suspend fun deleteTool(id: String)

    /**
     * Update a tool's definition.
     */
    suspend fun updateTool(id: String, request: DefineToolRequest): GuruDefinedTool

    // ==================== Tool Execution ====================

    /**
     * Execute a Guru-defined tool.
     */
    suspend fun executeTool(id: String, params: Map<String, Any?>): ToolExecutionResult

    /**
     * Execute a tool by name.
     */
    suspend fun executeToolByName(name: String, params: Map<String, Any?>): ToolExecutionResult

    /**
     * Record tool usage (updates lastUsedAt and useCount).
     */
    suspend fun recordUsage(id: String)

    // ==================== Summary ====================

    /**
     * Get a summary of Guru-defined tools.
     */
    suspend fun getSummary(): GuruToolsSummary

    // ==================== Validation ====================

    /**
     * Validate a tool definition before saving.
     */
    suspend fun validateToolDefinition(request: DefineToolRequest): ValidationResult

    /**
     * Check if a tool name is available.
     */
    suspend fun isNameAvailable(name: String): Boolean
}

/**
 * Result of validating a tool definition.
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)