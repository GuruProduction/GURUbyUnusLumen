package com.unuslumen.app.data.tools.registry

import com.unuslumen.app.domain.model.ToolCallResultObject

/**
 * ToolResultExtractor — Interface for extracting UI result objects from tool results.
 *
 * Each tool set has its own implementation. Takes the tool name, the raw JSON
 * result, and the typed result data if available.
 *
 * If resultData is sufficient to build the ToolCallResultObject, the extractor
 * uses it directly without parsing JSON. If not, it falls back to parsing
 * resultJson.
 *
 * This is the code currently in the 700-line extractResultObject when block
 * in AiRepositoryImpl, moved out to per-tool-set files.
 */
interface ToolResultExtractor {
    fun extract(toolName: String, resultJson: String, resultData: ToolResultData?): ToolCallResultObject?
}