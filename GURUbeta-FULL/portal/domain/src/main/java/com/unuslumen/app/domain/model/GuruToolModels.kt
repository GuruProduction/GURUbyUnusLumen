package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Status of a Guru-defined tool.
 */
enum class ToolStatus {
    PENDING,    // Awaiting user approval
    APPROVED,   // Active and available for use
    DISABLED     // Disabled by user
}

/**
 * Type of tool implementation.
 */
enum class ToolImplementationType {
    COMPOSITION,  // Compose existing tools
    SHELL,        // Execute shell command
    WEBHOOK        // Call external URL
}

/**
 * Represents a tool defined by Guru.
 */
@Serializable
data class GuruDefinedTool(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val parameters: JsonElement,      // JSON schema for parameters
    val implementation: ToolImplementation,
    val status: ToolStatus,
    val createdAt: Long,
    val approvedAt: Long?,
    val createdBy: String,
    val lastUsedAt: Long?,
    val useCount: Int,
    val rationale: String? = null
)

/**
 * Implementation definition for a Guru-defined tool.
 */
@Serializable
sealed class ToolImplementation {
    /**
     * Compose multiple existing tools in sequence.
     */
    @Serializable
    data class Composition(
        val steps: List<ToolStep>
    ) : ToolImplementation()

    /**
     * Execute a shell command with parameter interpolation.
     */
    @Serializable
    data class ShellCommand(
        val command: String,
        val params: List<String> = emptyList()
    ) : ToolImplementation()

    /**
     * Call an external webhook.
     */
    @Serializable
    data class Webhook(
        val url: String,
        val method: String = "POST",
        val headers: Map<String, String> = emptyMap()
    ) : ToolImplementation()
}

/**
 * A step in a composition tool.
 */
@Serializable
data class ToolStep(
    val tool: String,                    // Tool name to call
    val params: Map<String, String>,     // Parameters (can reference ${params.xxx} and ${previousStep.output})
    val output: String                   // Variable name for output
)

/**
 * Request to define a new tool.
 */
@Serializable
data class DefineToolRequest(
    val name: String,
    val displayName: String,
    val description: String,
    val parameters: JsonElement,
    val implementation: ToolImplementation,
    val rationale: String? = null
)

/**
 * Result of tool execution.
 */
@Serializable
data class ToolExecutionResult(
    val success: Boolean,
    val result: JsonElement?,
    val error: String? = null,
    val executionTimeMs: Long
)

/**
 * Summary of Guru-defined tools.
 */
@Serializable
data class GuruToolsSummary(
    val totalTools: Int,
    val approvedTools: Int,
    val pendingTools: Int,
    val disabledTools: Int,
    val totalUses: Int
)