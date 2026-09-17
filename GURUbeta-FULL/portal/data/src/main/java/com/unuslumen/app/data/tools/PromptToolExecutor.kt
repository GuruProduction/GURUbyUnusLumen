package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.repository.PromptRepository

class PromptToolExecutor(
    private val promptRepository: PromptRepository
) : ToolExecutor {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            PromptToolDefinitions.GET_SYSTEM_PROMPT -> {
                val prompt = promptRepository.getSystemPrompt()
                val rawJson = json.encodeToString(kotlinx.serialization.serializer<String>(), prompt)
                ToolExecutionResult(
                    rawJson = rawJson,
                    resultData = null,
                    resultObject = null,
                    success = true,
                    error = null
                )
            }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}