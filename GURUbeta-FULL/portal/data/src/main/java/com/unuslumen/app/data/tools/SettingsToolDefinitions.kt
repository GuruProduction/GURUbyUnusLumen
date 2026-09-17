package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object SettingsToolDefinitions : ToolSetRegistration {
    const val GET_PREFERENCE = "getPreference"
    const val SAVE_PREFERENCE = "savePreference"
    const val GET_ALL_PREFERENCES = "getAllPreferences"

    override val definitions = listOf(
        ToolDefinition(name = GET_PREFERENCE, description = "Get a preference value by key. Use this to check your human's settings — theme, language, AI provider, etc.", category = "settings", parameters = listOf(ToolParameter("key", ToolParameterType.String, true, "The preference key to look up. Common keys: ai_provider, ai_tools_enabled, user_name, theme, start_destination, calendar_exclude_patterns")), permissions = emptyList()),
        ToolDefinition(name = SAVE_PREFERENCE, description = "Save a preference value. Use this to change your human's settings. Be careful — some settings have immediate effects on the app.", category = "settings", parameters = listOf(ToolParameter("key", ToolParameterType.String, true, "The preference key to set"), ToolParameter("value", ToolParameterType.String, true, "The value to set.")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_PREFERENCES, description = "Get all known preference keys and their current values. Use this to see what settings are available.", category = "settings", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = SettingsToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}