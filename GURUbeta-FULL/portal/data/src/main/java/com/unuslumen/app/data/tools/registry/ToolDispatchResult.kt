package com.unuslumen.app.data.tools.registry

import com.unuslumen.app.domain.model.ToolCallResultObject

/**
 * DispatchStatus — The outcome of a dispatch attempt.
 *
 * AiRepositoryImpl branches on this to decide whether to trigger
 * PermissionGateway, return an error to the LLM, or proceed normally.
 */
enum class DispatchStatus {
    SUCCESS,
    UNKNOWN_TOOL,
    PERMISSION_DENIED,
    SECURITY_BLOCKED,
    EXECUTION_ERROR
}

/**
 * ToolDispatchResult — The return type of ToolDispatcher.dispatch().
 *
 * Carries everything AiRepositoryImpl needs from a single dispatch call:
 * the resolved tool name, the execution result, the UI result object,
 * and the dispatch status.
 *
 * For error cases (unknown tool, blocked, permission denied), the factory
 * functions produce a result with empty rawJson and null result data.
 * For success, the dispatcher constructs it from ToolExecutionResult.
 */
data class ToolDispatchResult(
    val resolvedName: String,
    val rawJson: String,
    val resultData: ToolResultData?,
    val resultObject: ToolCallResultObject?,
    val success: Boolean,
    val error: String?,
    val status: DispatchStatus
) {
    companion object {
        fun unknownTool(requestedName: String): ToolDispatchResult = ToolDispatchResult(
            resolvedName = requestedName,
            rawJson = "",
            resultData = null,
            resultObject = null,
            success = false,
            error = "Tool '$requestedName' is not registered",
            status = DispatchStatus.UNKNOWN_TOOL
        )

        fun permissionDenied(permission: String): ToolDispatchResult = ToolDispatchResult(
            resolvedName = "",
            rawJson = "",
            resultData = null,
            resultObject = null,
            success = false,
            error = "Permission denied: $permission",
            status = DispatchStatus.PERMISSION_DENIED
        )

        fun blocked(reason: String): ToolDispatchResult = ToolDispatchResult(
            resolvedName = "",
            rawJson = "",
            resultData = null,
            resultObject = null,
            success = false,
            error = reason,
            status = DispatchStatus.SECURITY_BLOCKED
        )

        fun executionError(message: String, resolvedName: String): ToolDispatchResult = ToolDispatchResult(
            resolvedName = resolvedName,
            rawJson = "",
            resultData = null,
            resultObject = null,
            success = false,
            error = message,
            status = DispatchStatus.EXECUTION_ERROR
        )

        fun success(
            resolvedName: String,
            rawJson: String,
            resultData: ToolResultData?,
            resultObject: ToolCallResultObject?,
            execSuccess: Boolean,
            execError: String?
        ): ToolDispatchResult = ToolDispatchResult(
            resolvedName = resolvedName,
            rawJson = rawJson,
            resultData = resultData,
            resultObject = resultObject,
            success = execSuccess,
            error = execError,
            status = if (execSuccess) DispatchStatus.SUCCESS else DispatchStatus.EXECUTION_ERROR
        )
    }
}