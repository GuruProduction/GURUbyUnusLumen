package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.domain.model.ToolCallResultObject
import com.unuslumen.app.domain.model.ToolResultSummary
import kotlinx.serialization.json.Json

class ToolResultToolResultExtractor : ToolResultExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    override fun extract(toolName: String, resultJson: String, resultData: ToolResultData?): ToolCallResultObject? {
        return when (toolName) {
            ToolResultToolDefinitions.GET_TOOL_RESULT,
            ToolResultToolDefinitions.SEARCH_TOOL_RESULTS -> runCatching {
                val result = resultData as? ToolResultRetrievalResult
                    ?: json.decodeFromString(ToolResultRetrievalResult.serializer(), resultJson)
                ToolCallResultObject.ToolResults(result.results.map {
                    ToolResultSummary(
                        toolName = it.toolName,
                        parameters = it.parameters,
                        result = it.result,
                        timestamp = it.timestamp,
                        ageMinutes = it.ageMinutes,
                        isStale = it.isStale
                    )
                })
            }.getOrNull()

            else -> null
        }
    }
}