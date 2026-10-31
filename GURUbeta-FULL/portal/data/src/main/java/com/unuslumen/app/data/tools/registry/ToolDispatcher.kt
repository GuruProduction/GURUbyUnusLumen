// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ToolDispatcher — Central dispatch for all tool execution.
 *
 * Full flow:
 * 1. Resolve the tool name via ToolNameResolver (passes rawArgs for disambiguation).
 * 2. If not in registry, return UNKNOWN_TOOL.
 * 3. Get the ToolDefinition.
 * 4. Check Android runtime permissions. If any required permission is not granted,
 *    return PERMISSION_DENIED. AiRepositoryImpl handles the PermissionGateway broadcast.
 * 5. Run ToolSecurityPolicy.check(definition, args).
 * 6. Run ToolArgumentRepair.repair(definition, args).
 * 7. Get the ToolExecutor and call execute().
 * 8. If resultObject is null and extractor exists, call extractor.extract().
 * 9. Return ToolDispatchResult.
 *
 * Step 7 also hands the outcome to ToolObservationRecorder when the call fails.
 * That write is fire-and-forget on the application scope, so it can never delay
 * or fail the dispatch and it changes nothing about what the dispatcher returns.
 */
class ToolDispatcher(
    private val registry: ToolRegistry,
    private val nameResolver: ToolNameResolver,
    private val securityPolicy: ToolSecurityPolicy,
    private val argumentRepair: ToolArgumentRepair,
    private val context: Context,
    private val observationRecorder: ToolObservationRecorder? = null
) {
    suspend fun dispatch(
        toolName: String,
        rawArgs: JsonObject,
        conversationId: String? = null
    ): ToolDispatchResult {
        val resolvedName = nameResolver.resolve(toolName, registry, rawArgs)
            ?: return ToolDispatchResult.unknownTool(toolName)

        val definition = registry.getDefinition(resolvedName)
            ?: return ToolDispatchResult.unknownTool(resolvedName)

        // Check Android runtime permissions.
        //
        // The refusal is built from the same catalogue every tool's own guard uses,
        // because this check runs BEFORE the executor and therefore decides what
        // the model actually reads. A bare "Permission denied: android.permission.X"
        // names no route and does not say whether a dialog could fix it at all, so
        // a person is left holding a platform string instead of an instruction.
        for (perm in definition.permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return ToolDispatchResult.permissionDenied(
                    PermissionGrants.refusalForPermission(context, perm)
                )
            }
        }

        val securityCheck = securityPolicy.check(definition, rawArgs)
        if (securityCheck.blocked) return ToolDispatchResult.blocked(securityCheck.reason)

        val repairedArgs = argumentRepair.repair(definition, rawArgs)

        val executor = registry.getExecutor(resolvedName)
            ?: return ToolDispatchResult.unknownTool(resolvedName)

        val startedAt = System.currentTimeMillis()
        val execResult = executor.execute(resolvedName, repairedArgs)
        val durationMs = System.currentTimeMillis() - startedAt

        if (failed(execResult)) {
            // The executor's own error when there is one, else the head of
            // whatever it did return. Never invented: a failure with no message
            // stores the empty string and stays honest.
            observationRecorder?.recordFailure(
                packageName = null,
                activityClass = null,
                toolName = resolvedName,
                argumentsJson = rawArgs.toString(),
                resultSummary = execResult.error ?: execResult.rawJson.take(500),
                conversationId = conversationId,
                durationMs = durationMs,
                targetValues = observationRecorder.targetValuesFrom(repairedArgs)
            )
        }

        val resultObject = execResult.resultObject
            ?: registry.getExtractor(resolvedName)?.extract(resolvedName, execResult.rawJson, execResult.resultData)

        return ToolDispatchResult(
            resolvedName = resolvedName,
            rawJson = execResult.rawJson,
            resultData = execResult.resultData,
            resultObject = resultObject,
            success = execResult.success,
            error = execResult.error,
            status = if (execResult.success) DispatchStatus.SUCCESS else DispatchStatus.EXECUTION_ERROR
        )
    }

    /**
     * Did this call actually fail?
     *
     * Two shapes of failure reach the dispatcher. An executor that lets the
     * error out returns success=false directly. An executor that catches its own
     * error — which is every accessibility tool, because a failed tap is a real
     * answer rather than an exception — returns success=true and reports the
     * failure in its payload as a boolean `success` field. The payload case is
     * the one that matters most for the log: it is precisely a "clicked the
     * wrong thing" outcome that the next attempt needs the recorded tree for.
     *
     * The payload test parses the JSON rather than searching for the substring,
     * so a tool that legitimately returns text mentioning `"success": false`
     * (reading a config file, echoing a log) is not mistaken for a failure. The
     * cheap substring gate runs first so the parse is skipped on nearly every
     * result.
     *
     * This decides only whether a row is written. Nothing about what the
     * dispatcher returns changes.
     */
    private fun failed(result: ToolExecutionResult): Boolean {
        if (!result.success) return true
        val body = result.rawJson
        if (body.isBlank() || !body.contains("success")) return false
        val reported = runCatching {
            (Json.parseToJsonElement(body) as? JsonObject)
                ?.get("success")
                ?.let { it as? JsonPrimitive }
                ?.takeIf { !it.isString }
                ?.content
        }.getOrNull() ?: return false
        return reported == "false"
    }

}
