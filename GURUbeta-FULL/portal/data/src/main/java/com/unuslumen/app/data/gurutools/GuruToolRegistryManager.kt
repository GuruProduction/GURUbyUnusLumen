package com.unuslumen.app.data.gurutools

import ai.koog.agents.core.tools.ToolDescriptor
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.ToolStatus
import com.unuslumen.app.domain.repository.GuruToolRepository
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory

/**
 * Manages dynamic registration of Guru-defined tools.
 *
 * Guru-defined tools produce [ToolDescriptor] objects that get merged with
 * built-in tool descriptors before being sent to the LLM. Execution is handled
 * separately via [DynamicToolExecutor] — see [AiRepositoryImpl.executeToolCall].
 */
@Factory
class GuruToolRegistryManager(
    private val guruToolRepository: GuruToolRepository,
    private val dynamicToolExecutor: DynamicToolExecutor
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Build ToolDescriptors from all approved Guru-defined tools.
     * These get merged with built-in descriptors before each LLM request.
     */
    suspend fun getGuruToolDescriptors(): List<ToolDescriptor> {
        val approvedTools = guruToolRepository.getApprovedTools()
        return approvedTools.map { tool ->
            ToolDescriptor(
                name = tool.name,
                description = tool.description
            )
        }
    }

    /**
     * Execute a Guru-defined tool by name. Returns the result as a JSON string.
     * Called from [AiRepositoryImpl] when a tool call matches a Guru-defined tool name.
     */
    suspend fun executeGuruTool(name: String, args: Map<String, Any?>): String {
        val tool = guruToolRepository.getToolByName(name)
            ?: throw IllegalArgumentException("Guru-defined tool not found: $name")

        if (tool.status != ToolStatus.APPROVED) {
            throw IllegalStateException("Tool '$name' is not approved for execution")
        }

        val result = dynamicToolExecutor.execute(tool, args)
        return result.toString()
    }

    /**
     * Check if a tool name belongs to an approved Guru-defined tool.
     */
    suspend fun isGuruTool(name: String): Boolean {
        val tool = guruToolRepository.getToolByName(name) ?: return false
        return tool.status == ToolStatus.APPROVED
    }

    /**
     * Refresh the combined tool descriptors (built-in + Guru-defined).
     * Call this after any tool creation, approval, or deletion.
     */
    suspend fun refreshToolDescriptors(
        builtInDescriptors: List<ToolDescriptor>
    ): List<ToolDescriptor> {
        val guruDescriptors = getGuruToolDescriptors()
        return builtInDescriptors + guruDescriptors
    }
}
