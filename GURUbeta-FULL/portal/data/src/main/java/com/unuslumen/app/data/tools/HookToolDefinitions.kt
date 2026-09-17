package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object HookToolDefinitions : ToolSetRegistration {
    const val CREATE_HOOK = "createHook"; const val LIST_HOOKS = "listHooks"; const val GET_HOOK = "getHook"
    const val DELETE_HOOK = "deleteHook"; const val ENABLE_HOOK = "enableHook"; const val DISABLE_HOOK = "disableHook"; const val TRIGGER_HOOK = "triggerHook"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_HOOK, description = "Create a hook - an event-triggered action.", category = "hook", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Hook name"), ToolParameter("displayName", ToolParameterType.String, true, "Display name"), ToolParameter("description", ToolParameterType.String, true, "Description"), ToolParameter("eventType", ToolParameterType.String, true, "Event type"), ToolParameter("triggerTiming", ToolParameterType.String, true, "BEFORE or AFTER"), ToolParameter("condition", ToolParameterType.String, false, "JSON condition"), ToolParameter("action", ToolParameterType.String, true, "JSON action"), ToolParameter("priority", ToolParameterType.Integer, true, "Priority")), permissions = emptyList()),
        ToolDefinition(name = LIST_HOOKS, description = "List all hooks.", category = "hook", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_HOOK, description = "Get detailed information about a specific hook.", category = "hook", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Hook name or ID")), permissions = emptyList()),
        ToolDefinition(name = DELETE_HOOK, description = "Delete a hook. This cannot be undone.", category = "hook", parameters = listOf(ToolParameter("hookId", ToolParameterType.String, true, "Hook ID")), permissions = emptyList()),
        ToolDefinition(name = ENABLE_HOOK, description = "Enable a disabled hook.", category = "hook", parameters = listOf(ToolParameter("hookId", ToolParameterType.String, true, "Hook ID")), permissions = emptyList()),
        ToolDefinition(name = DISABLE_HOOK, description = "Disable a hook.", category = "hook", parameters = listOf(ToolParameter("hookId", ToolParameterType.String, true, "Hook ID")), permissions = emptyList()),
        ToolDefinition(name = TRIGGER_HOOK, description = "Manually trigger a hook for testing.", category = "hook", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Hook name or ID"), ToolParameter("eventData", ToolParameterType.String, true, "Event data JSON")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = HookToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}