package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.ToolImplementation
import com.unuslumen.app.domain.model.ToolStep
import com.unuslumen.app.domain.repository.GuruToolRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MetaToolExecutor(private val guruToolRepository: GuruToolRepository) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        MetaToolDefinitions.DEFINE_TOOL -> defineTool(args)
        MetaToolDefinitions.GET_MY_TOOLS -> { val tools = guruToolRepository.getAllTools(); val r = MyToolsResult(tools.map { it.toInfo() }, guruToolRepository.getSummary()); ToolExecutionResult.success(r, json.encodeToString(MyToolsResult.serializer(), r)) }
        MetaToolDefinitions.GET_TOOL_DETAILS -> { val id = args["toolId"] as? String ?: return ToolExecutionResult.error("Missing 'toolId'"); val tool = guruToolRepository.getTool(id) ?: guruToolRepository.getToolByName(id) ?: return ToolExecutionResult.error("Not found: $id"); val r = ToolDetailsResult(tool.toDetailedInfo()); ToolExecutionResult.success(r, json.encodeToString(ToolDetailsResult.serializer(), r)) }
        MetaToolDefinitions.DELETE_TOOL_TOOL -> { val id = args["toolId"] as? String ?: return ToolExecutionResult.error("Missing 'toolId'"); val tool = guruToolRepository.getTool(id) ?: return ToolExecutionResult.error("Not found: $id"); guruToolRepository.deleteTool(id); val r = DeleteToolResult(id, tool.name, "Deleted."); ToolExecutionResult.success(r, json.encodeToString(DeleteToolResult.serializer(), r)) }
        MetaToolDefinitions.APPROVE_TOOL -> { val id = args["toolId"] as? String ?: return ToolExecutionResult.error("Missing 'toolId'"); val tool = guruToolRepository.approveTool(id); val r = ApproveToolResult(id, tool.name, "Approved."); ToolExecutionResult.success(r, json.encodeToString(ApproveToolResult.serializer(), r)) }
        MetaToolDefinitions.DISABLE_TOOL -> { val id = args["toolId"] as? String ?: return ToolExecutionResult.error("Missing 'toolId'"); val tool = guruToolRepository.disableTool(id); val r = DisableToolResult(id, tool.name, "Disabled."); ToolExecutionResult.success(r, json.encodeToString(DisableToolResult.serializer(), r)) }
        MetaToolDefinitions.ENABLE_TOOL -> { val id = args["toolId"] as? String ?: return ToolExecutionResult.error("Missing 'toolId'"); val tool = guruToolRepository.enableTool(id); val r = EnableToolResult(id, tool.name, "Enabled."); ToolExecutionResult.success(r, json.encodeToString(EnableToolResult.serializer(), r)) }
        MetaToolDefinitions.UPDATE_TOOL -> updateTool(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun defineTool(args: Map<String, Any?>): ToolExecutionResult {
        val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val displayName = args["displayName"] as? String ?: return ToolExecutionResult.error("Missing 'displayName'")
        val description = args["description"] as? String ?: return ToolExecutionResult.error("Missing 'description'")
        val parameters = args["parameters"] as? String ?: return ToolExecutionResult.error("Missing 'parameters'")
        val implementation = args["implementation"] as? String ?: return ToolExecutionResult.error("Missing 'implementation'")
        val rationale = args["rationale"] as? String ?: return ToolExecutionResult.error("Missing 'rationale'")
        val sanitizedName = name.lowercase().replace(Regex("[^a-z0-9_]"), "_").replace(Regex("^_+|_+$"), "")
        if (!guruToolRepository.isNameAvailable(sanitizedName)) return ToolExecutionResult.error("Name '$sanitizedName' already taken")
        val paramsJson = json.parseToJsonElement(parameters)
        val implJson = json.parseToJsonElement(implementation)
        val parsedImpl = parseImplementation(implJson)
        val req = com.unuslumen.app.domain.model.DefineToolRequest(sanitizedName, displayName, description, paramsJson, parsedImpl, rationale)
        val validation = guruToolRepository.validateToolDefinition(req)
        if (!validation.valid) return ToolExecutionResult.error("Invalid: ${validation.errors.joinToString(";")}")
        val tool = guruToolRepository.defineTool(req)
        val isAutoApprovable = parsedImpl is ToolImplementation.Composition
        val finalTool = if (isAutoApprovable) guruToolRepository.approveTool(tool.id) else tool
        val r = DefineToolResult(finalTool.id, finalTool.name, finalTool.displayName, finalTool.status.name, if (isAutoApprovable) "Composition tool defined and auto-approved." else "Tool defined. Requires human approval.")
        return ToolExecutionResult.success(r, json.encodeToString(DefineToolResult.serializer(), r))
    }

    private suspend fun updateTool(args: Map<String, Any?>): ToolExecutionResult {
        val toolId = args["toolId"] as? String ?: return ToolExecutionResult.error("Missing 'toolId'")
        val existing = guruToolRepository.getTool(toolId) ?: return ToolExecutionResult.error("Not found: $toolId")
        val implStr = args["implementation"] as? String
        val parsedImpl = implStr?.let { parseImplementation(json.parseToJsonElement(it)) } ?: existing.implementation
        val req = com.unuslumen.app.domain.model.DefineToolRequest(existing.name, (args["displayName"] as? String) ?: existing.displayName, (args["description"] as? String) ?: existing.description, existing.parameters, parsedImpl, existing.rationale)
        val updated = guruToolRepository.updateTool(toolId, req)
        val finalTool = if (parsedImpl is ToolImplementation.Composition) guruToolRepository.approveTool(updated.id) else updated
        val r = UpdateToolResult(finalTool.id, finalTool.name, finalTool.status.name, "Updated.")
        return ToolExecutionResult.success(r, json.encodeToString(UpdateToolResult.serializer(), r))
    }

    private fun parseImplementation(jsonElement: JsonElement): ToolImplementation {
        val obj = jsonElement.jsonObject; val type = obj["type"]?.jsonPrimitive?.content
        return when (type) {
            "composition" -> { val steps = obj["steps"]?.jsonArray?.map { se -> val so = se.jsonObject; ToolStep(so["tool"]?.jsonPrimitive?.content ?: "", so["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(), so["output"]?.jsonPrimitive?.content ?: "") } ?: emptyList(); ToolImplementation.Composition(steps) }
            "shell" -> ToolImplementation.ShellCommand(obj["command"]?.jsonPrimitive?.content ?: "", obj["params"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
            "webhook" -> ToolImplementation.Webhook(obj["url"]?.jsonPrimitive?.content ?: "", obj["method"]?.jsonPrimitive?.content ?: "POST", obj["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap())
            else -> throw IllegalArgumentException("Unknown type: $type")
        }
    }

    private fun GuruDefinedTool.toInfo() = ToolInfo(id, name, displayName, description, status.name, useCount, createdAt)
    private fun GuruDefinedTool.toDetailedInfo() = DetailedToolInfo(id, name, displayName, description, parameters.toString(), implementation.toString(), status.name, createdAt, approvedAt, createdBy, lastUsedAt, useCount, rationale)
}