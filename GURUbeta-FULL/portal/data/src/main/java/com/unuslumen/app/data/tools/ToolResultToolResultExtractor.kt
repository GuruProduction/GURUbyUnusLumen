// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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