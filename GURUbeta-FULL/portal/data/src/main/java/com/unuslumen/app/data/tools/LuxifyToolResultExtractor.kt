// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.domain.model.ToolCallResultObject
import kotlinx.serialization.json.Json

class LuxifyToolResultExtractor : ToolResultExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    override fun extract(toolName: String, resultJson: String, resultData: ToolResultData?): ToolCallResultObject? {
        return when (toolName) {
            LuxifyToolDefinitions.USE_SKILL -> runCatching {
                val result = resultData as? UseSkillResult
                    ?: json.decodeFromString(UseSkillResult.serializer(), resultJson)
                ToolCallResultObject.SkillLoaded(
                    skillName = result.skillName,
                    description = result.description,
                    toolsAllowed = result.allowedTools,
                    success = result.success,
                    error = result.error
                )
            }.getOrNull()

            else -> null
        }
    }
}