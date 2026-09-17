package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.domain.model.ToolCallResultObject
import com.unuslumen.app.domain.model.WebSearchItem
import kotlinx.serialization.json.Json

class WebToolResultExtractor : ToolResultExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    override fun extract(toolName: String, resultJson: String, resultData: ToolResultData?): ToolCallResultObject? {
        return when (toolName) {
            WebToolDefinitions.WEB_SEARCH -> runCatching {
                val result = resultData as? WebSearchResult
                    ?: json.decodeFromString(WebSearchResult.serializer(), resultJson)
                ToolCallResultObject.WebResults(result.results.map { WebSearchItem(it.title, it.url, it.snippet) })
            }.getOrNull()
            else -> null
        }
    }
}