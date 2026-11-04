// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
        /*
         * The three accepted schema shapes are stated IN THE CONTRACT, not only in
         * the mapper's source. Issue 296 measured the cost of leaving them out: a
         * model read "JSON schema for parameters", wrote the obvious flat map, and
         * got a tool where every argument was mandatory, because it had no way to
         * know a `required` array existed or that a per-property hint was honoured.
         *
         * The description is long because this is a contract a language model has
         * to satisfy on the first attempt, and the difference between the shapes
         * is the difference between an optional argument and a mandatory one.
         */
        ToolDefinition(
            name = DEFINE_TOOL,
            description = "Define a new tool for yourself. Composition (chained existing tools), shell command, or webhook — every kind registers immediately with no approval step, and is callable from your very next request. " +
                "PARAMETERS accepts three shapes: " +
                "(1) JSON Schema form {\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]} — the required array is honoured exactly; " +
                "(2) flat form {\"a\":{\"type\":\"string\",\"description\":\"what it is\"}} — every property is required unless it carries \"required\":false; " +
                "(3) map form {\"a\":\"string\"} — a bare type name per argument, all required. " +
                "In any shape, a property may carry \"required\":false or \"optional\":true to make it optional.",
            category = "meta",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Tool name (lowercase, underscores)"),
                ToolParameter("displayName", ToolParameterType.String, true, "Human-readable name"),
                ToolParameter("description", ToolParameterType.String, true, "Description"),
                ToolParameter("parameters", ToolParameterType.String, true, "Parameter schema, as any of the three shapes described"),
                ToolParameter("implementation", ToolParameterType.String, true, "Implementation JSON"),
                ToolParameter("rationale", ToolParameterType.String, true, "Why this tool is needed"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(name = GET_MY_TOOLS, description = "List all tools you've defined. Shows both approved and pending tools.", category = "meta", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_TOOL_DETAILS, description = "Get detailed information about a specific tool you've defined.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool name or ID")), permissions = emptyList()),
        /*
         * `parameters` is here because the schema is editable (issue 297). Every
         * field but toolId is optional and falls back to the stored value, so a
         * caller changing one thing does not resend the rest.
         */
        ToolDefinition(
            name = UPDATE_TOOL,
            description = "Update a tool you've defined, including its parameter schema. Everything except toolId is optional and anything omitted keeps its current value. Changes take effect immediately; the tool stays live and keeps its id, its use count and every reference to it. " +
                "The result is validated exactly as defineTool validates it, so an invalid schema is refused rather than saved.",
            category = "meta",
            parameters = listOf(
                ToolParameter("toolId", ToolParameterType.String, true, "Tool ID"),
                ToolParameter("displayName", ToolParameterType.String, false, "New display name"),
                ToolParameter("description", ToolParameterType.String, false, "New description"),
                ToolParameter(
                    "parameters",
                    ToolParameterType.String,
                    false,
                    "New parameter schema, in any of the shapes defineTool accepts. Use a per-property \"required\":false or \"optional\":true to mark an argument optional.",
                ),
                ToolParameter("implementation", ToolParameterType.String, false, "New implementation JSON"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(name = DELETE_TOOL_TOOL, description = "Delete a tool you've defined. This cannot be undone.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList()),
        ToolDefinition(name = APPROVE_TOOL, description = "Approve a pending tool. USER tool only.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList()),
        ToolDefinition(name = DISABLE_TOOL, description = "Disable an approved tool. USER tool only.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList()),
        ToolDefinition(name = ENABLE_TOOL, description = "Re-enable a disabled tool. USER tool only.", category = "meta", parameters = listOf(ToolParameter("toolId", ToolParameterType.String, true, "Tool ID")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = MetaToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}