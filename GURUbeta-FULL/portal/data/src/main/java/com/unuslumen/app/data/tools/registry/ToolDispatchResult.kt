// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

import com.unuslumen.app.data.tools.registry.permission.GrantCheck
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
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

        /**
         * Refuse a dispatch because a declared permission is not held.
         *
         * Takes the resolved [GrantCheck] rather than a raw permission string,
         * because the sentence is what the model reads and what the person acts on.
         * The bare form this replaced named no route and gave no indication whether
         * a dialog could fix it, so the model had nothing useful to say and the
         * person had nothing to follow.
         *
         * `rawJson` carries the same structured refusal body a tool's own guard
         * returns, so a caller sees one refusal shape whichever path refused.
         */
        fun permissionDenied(check: GrantCheck): ToolDispatchResult = ToolDispatchResult(
            resolvedName = "",
            rawJson = PermissionGrants.refusalBodyJson(check),
            resultData = null,
            resultObject = null,
            success = false,
            error = check.sentence(),
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