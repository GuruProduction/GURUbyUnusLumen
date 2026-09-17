package com.unuslumen.app.data.tools.registry

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject

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
 */
class ToolDispatcher(
    private val registry: ToolRegistry,
    private val nameResolver: ToolNameResolver,
    private val securityPolicy: ToolSecurityPolicy,
    private val argumentRepair: ToolArgumentRepair,
    private val context: Context
) {
    suspend fun dispatch(toolName: String, rawArgs: JsonObject): ToolDispatchResult {
        val resolvedName = nameResolver.resolve(toolName, registry, rawArgs)
            ?: return ToolDispatchResult.unknownTool(toolName)

        val definition = registry.getDefinition(resolvedName)
            ?: return ToolDispatchResult.unknownTool(resolvedName)

        // Check Android runtime permissions
        for (perm in definition.permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return ToolDispatchResult.permissionDenied(perm)
            }
        }

        val securityCheck = securityPolicy.check(definition, rawArgs)
        if (securityCheck.blocked) return ToolDispatchResult.blocked(securityCheck.reason)

        val repairedArgs = argumentRepair.repair(definition, rawArgs)

        val executor = registry.getExecutor(resolvedName)
            ?: return ToolDispatchResult.unknownTool(resolvedName)

        val execResult = executor.execute(resolvedName, repairedArgs)

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
}