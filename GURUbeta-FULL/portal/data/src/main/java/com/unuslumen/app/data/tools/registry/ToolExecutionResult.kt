package com.unuslumen.app.data.tools.registry

import com.unuslumen.app.domain.model.ToolCallResultObject

/**
 * ToolExecutionResult — What an executor returns.
 *
 * Carries both the raw JSON string and the typed result object directly.
 * The executor produces the typed result, serializes it to JSON for
 * storage and logging, but also passes the typed object through.
 *
 * The dispatcher checks resultObject first. If the executor already
 * populated it, the extractor is skipped entirely. If resultObject is
 * null, the dispatcher calls the extractor with rawJson to produce it.
 *
 * This avoids double deserialization. Simple tools can have their executor
 * populate resultObject directly. Complex tools leave it null and let
 * the extractor handle it.
 */
data class ToolExecutionResult(
    val rawJson: String,
    val resultData: ToolResultData?,
    val resultObject: ToolCallResultObject?,
    val success: Boolean,
    val error: String?
) {
    companion object {
        fun success(resultData: ToolResultData, rawJson: String, resultObject: ToolCallResultObject? = null): ToolExecutionResult {
            return ToolExecutionResult(
                rawJson = rawJson,
                resultData = resultData,
                resultObject = resultObject,
                success = true,
                error = null
            )
        }

        fun error(message: String): ToolExecutionResult {
            return ToolExecutionResult(
                rawJson = "",
                resultData = null,
                resultObject = null,
                success = false,
                error = message
            )
        }
    }
}