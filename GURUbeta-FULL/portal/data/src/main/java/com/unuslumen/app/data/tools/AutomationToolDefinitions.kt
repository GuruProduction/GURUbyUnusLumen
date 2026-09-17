package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object AutomationToolDefinitions : ToolSetRegistration {
    const val CREATE_AUTOMATION = "createAutomation"; const val RUN_AUTOMATION = "runAutomation"
    const val LIST_AUTOMATIONS = "listAutomations"; const val GET_AUTOMATION = "getAutomation"
    const val DELETE_AUTOMATION = "deleteAutomation"; const val ENABLE_AUTOMATION = "enableAutomation"; const val DISABLE_AUTOMATION = "disableAutomation"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_AUTOMATION, description = "Create a reusable automation - a named sequence of tool calls.", category = "automation", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Automation name"), ToolParameter("displayName", ToolParameterType.String, true, "Display name"), ToolParameter("description", ToolParameterType.String, true, "Description"), ToolParameter("trigger", ToolParameterType.String, true, "MANUAL, SCHEDULED, or EVENT"), ToolParameter("triggerConfig", ToolParameterType.String, false, "JSON trigger config"), ToolParameter("steps", ToolParameterType.String, true, "JSON array of steps")), permissions = emptyList()),
        ToolDefinition(name = RUN_AUTOMATION, description = "Run an automation by name.", category = "automation", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Automation name"), ToolParameter("params", ToolParameterType.String, false, "Parameters JSON")), permissions = emptyList()),
        ToolDefinition(name = LIST_AUTOMATIONS, description = "List all available automations.", category = "automation", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_AUTOMATION, description = "Get detailed information about a specific automation.", category = "automation", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Automation name or ID")), permissions = emptyList()),
        ToolDefinition(name = DELETE_AUTOMATION, description = "Delete an automation. This cannot be undone.", category = "automation", parameters = listOf(ToolParameter("automationId", ToolParameterType.String, true, "Automation ID")), permissions = emptyList()),
        ToolDefinition(name = ENABLE_AUTOMATION, description = "Enable a disabled automation.", category = "automation", parameters = listOf(ToolParameter("automationId", ToolParameterType.String, true, "Automation ID")), permissions = emptyList()),
        ToolDefinition(name = DISABLE_AUTOMATION, description = "Disable an automation.", category = "automation", parameters = listOf(ToolParameter("automationId", ToolParameterType.String, true, "Automation ID")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = AutomationToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}