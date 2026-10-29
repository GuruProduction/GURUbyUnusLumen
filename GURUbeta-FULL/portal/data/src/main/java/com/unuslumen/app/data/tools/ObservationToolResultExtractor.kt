// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.domain.model.ToolCallResultObject
import com.unuslumen.app.domain.model.ToolResultSummary
import kotlinx.serialization.json.Json

/**
 * ObservationToolResultExtractor — the UI object for a retrieval.
 *
 * An observation read is a list of past tool runs, which is exactly what the
 * existing ToolResults card renders: tool name, arguments, result and time, with
 * the staleness line that already tells the model to re-call. Reusing that card
 * means the log appears in the chat surface without any new presentation type.
 * The tree stays in the JSON body the model receives; the card carries the
 * human-readable half.
 */
class ObservationToolResultExtractor : ToolResultExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    override fun extract(toolName: String, resultJson: String, resultData: ToolResultData?): ToolCallResultObject? {
        return when (toolName) {
            ObservationToolDefinitions.GET_OBSERVATIONS -> runCatching {
                val result = resultData as? ToolObservationLogResult
                    ?: json.decodeFromString(ToolObservationLogResult.serializer(), resultJson)
                ToolCallResultObject.ToolResults(result.observations.map {
                    ToolResultSummary(
                        toolName = it.toolName,
                        parameters = it.argumentsJson,
                        result = it.resultSummary.ifBlank {
                            if (it.success) "Recorded as a success." else "Recorded as a failure."
                        },
                        timestamp = it.timestamp,
                        ageMinutes = it.ageMinutes,
                        isStale = false
                    )
                })
            }.getOrNull()

            else -> null
        }
    }
}
