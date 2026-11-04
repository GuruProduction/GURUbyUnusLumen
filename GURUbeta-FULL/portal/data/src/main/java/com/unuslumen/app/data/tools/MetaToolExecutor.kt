// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.ToolImplementation
import com.unuslumen.app.domain.model.ToolStep
import com.unuslumen.app.domain.repository.GuruToolRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
        // No approval wall, no second write: defineTool persists the row as
        // APPROVED directly (the PENDING hop raced a two-stage write and could
        // strand tools as unapproved ghosts — issue #3's tail).
        val r = DefineToolResult(tool.id, tool.name, tool.displayName, tool.status.name, "Tool defined and registered. Live from your next request.")
        return ToolExecutionResult.success(r, json.encodeToString(DefineToolResult.serializer(), r))
    }

    /**
     * Change a tool that already exists, including its parameter schema.
     *
     * ISSUE 297: `parameters` was not accepted at all. The rebuilt request passed
     * `existing.parameters` through every time, so the schema was write-once and
     * the only way to repair a wrong one was delete-and-redefine — which destroys
     * the tool's id, its use count, its creation time and every automation that
     * references it by name. A model writing a JSON schema by hand gets it wrong
     * often enough that a repair path has to exist, and destroying history is not
     * a repair.
     *
     * VALIDATION IS THE SAME ONE defineTool USES, and it runs before the write.
     * That matters more here than at define time: an edit to a live tool can break
     * a working composition, so an invalid schema has to be refused with the same
     * error surface rather than persisted and discovered on the next call.
     *
     * Every field is optional and each falls back to the stored value, so a caller
     * changing one thing does not have to resend the rest. `parameters` accepts
     * either a JSON string or an already-structured element, because a model
     * calling this tool sometimes passes the schema as text and sometimes as an
     * object, and refusing the object form would be a trap.
     */
    private suspend fun updateTool(args: Map<String, Any?>): ToolExecutionResult {
        val toolId = args["toolId"] as? String ?: return ToolExecutionResult.error("Missing 'toolId'")
        val existing = guruToolRepository.getTool(toolId) ?: return ToolExecutionResult.error("Not found: $toolId")

        val implStr = args["implementation"] as? String
        val parsedImpl = implStr?.let { parseImplementation(json.parseToJsonElement(it)) } ?: existing.implementation

        // The repair path this issue is about. A string is parsed; a map or list
        // is used as-is; absent means "leave the schema alone".
        val parametersElement: JsonElement = when (val raw = args["parameters"]) {
            null -> existing.parameters
            is String -> {
                if (raw.isBlank()) existing.parameters else runCatching { json.parseToJsonElement(raw) }
                    .getOrElse { return ToolExecutionResult.error("Invalid 'parameters' JSON: ${it.message}") }
            }
            is JsonElement -> raw
            else -> return ToolExecutionResult.error(
                "'parameters' must be a JSON string or object, got ${raw::class.simpleName}",
            )
        }

        val req = com.unuslumen.app.domain.model.DefineToolRequest(
            existing.name,
            (args["displayName"] as? String) ?: existing.displayName,
            (args["description"] as? String) ?: existing.description,
            parametersElement,
            parsedImpl,
            existing.rationale,
        )

        // Refuse an invalid definition BEFORE it reaches the database. The same
        // check defineTool runs, so an edit and a create reject the same things
        // with the same sentences.
        val validation = guruToolRepository.validateToolDefinition(req)
        if (!validation.valid) return ToolExecutionResult.error("Invalid: ${validation.errors.joinToString(";")}")

        val updated = guruToolRepository.updateTool(toolId, req)
        // No trailing approve call: row keeps its APPROVED status through the
        // edit (the repo no longer resets to PENDING), one clean write.
        val changed = when (args["parameters"]) {
            null -> "Updated."
            else -> "Updated, including the parameter schema. The new contract is live from your next request."
        }
        val r = UpdateToolResult(updated.id, updated.name, updated.status.name, changed)
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