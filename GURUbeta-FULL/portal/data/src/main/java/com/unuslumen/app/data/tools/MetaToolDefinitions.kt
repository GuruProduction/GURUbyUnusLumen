package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object MetaToolDefinitions : ToolSetRegistration {
    const val DEFINE_TOOL = "defineTool"
    const val GET_MY_TOOLS = "getMyTools"
    const val GET_TOOL_DETAILS = "getToolDetails"
    const val UPDATE_TOOL = "updateTool"
    const val DELETE_TOOL_TOOL = "deleteTool"
    const val APPROVE_TOOL = "approveTool"
    const val DISABLE_TOOL = "disableTool"
    const val ENABLE_TOOL = "enableTool"

    override val definitions = listOf(
        ToolDefinition(name = DEFINE_TOOL, description = "Define a new tool for yourself by composing existing tools. Composition tools are auto-approved. Shell commands and webhook tools require user approval.", category = "meta", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Tool name (lowercase, underscores)"), ToolParameter("displayName", ToolParameterType.String, true, "Human-readable name"), ToolParameter("description", ToolParameterType.String, true, "Description"), ToolParameter("parameters", ToolParameterType.String, true, "JSON schema for parameters"), ToolParameter("implementation", ToolParameterType.String, true, "Implementation JSON"), ToolParameter("rationale", ToolParameterType.String, true, "Why this tool is needed")), permissions = emptyList()),
        ToolDefinition(name = GET_MY_TOOLS, description = "List all tools you've defined. Shows both approved and pending tools.", category = "meta", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_TOOL_DETAILS, description = "Get detailed information about a specific tool you've defined.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool name or ID")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_TOOL, description = "Update a tool you've defined. Composition tools are auto-approved on update.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID"), ToolParameter("displayName", ToolParameterType.String, false, "New display name"), ToolParameter("description", ToolParameterType.String, false, "New description"), ToolParameter("implementation", ToolParameterType.String, false, "New implementation JSON")), permissions = emptyList()),
        ToolDefinition(name = DELETE_TOOL_TOOL, description = "Delete a tool you've defined. This cannot be undone.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList()),
        ToolDefinition(name = APPROVE_TOOL, description = "Approve a pending tool. USER tool only.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList()),
        ToolDefinition(name = DISABLE_TOOL, description = "Disable an approved tool. USER tool only.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList()),
        ToolDefinition(name = ENABLE_TOOL, description = "Re-enable a disabled tool. USER tool only.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = MetaToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}