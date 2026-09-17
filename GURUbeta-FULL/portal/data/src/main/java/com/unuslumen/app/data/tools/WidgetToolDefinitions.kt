package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object WidgetToolDefinitions : ToolSetRegistration {
    const val CREATE_SHORTCUT = "createShortcut"
    const val LIST_SHORTCUTS = "listShortcuts"
    const val REMOVE_SHORTCUT = "removeShortcut"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_SHORTCUT, description = "Create a homescreen shortcut that launches a specific action. Use when your human wants quick access to something you've built — a skill, a tool, a URL, or any intent-based action.", category = "widget", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Short name for the shortcut, e.g. 'Morning Briefing'"), ToolParameter("action", ToolParameterType.String, false, "Intent action, e.g. 'android.intent.action.VIEW'"), ToolParameter("dataUri", ToolParameterType.String, false, "Data URI for the intent, e.g. 'https://example.com' or 'guru://skill/morning_briefing'"), ToolParameter("description", ToolParameterType.String, false, "Short description shown on the shortcut")), permissions = emptyList()),
        ToolDefinition(name = LIST_SHORTCUTS, description = "List all dynamic shortcuts currently pinned to the launcher.", category = "widget", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = REMOVE_SHORTCUT, description = "Remove a dynamic shortcut by ID.", category = "widget", parameters = listOf(ToolParameter("shortcutId", ToolParameterType.String, true, "Shortcut ID from listShortcuts")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = WidgetToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}