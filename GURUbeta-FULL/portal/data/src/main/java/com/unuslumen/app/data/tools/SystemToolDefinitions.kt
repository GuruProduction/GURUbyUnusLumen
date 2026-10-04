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

object SystemToolDefinitions : ToolSetRegistration {
    const val HEALTH_CHECK = "healthCheck"
    const val DEVICE_INFO = "deviceInfo"
    const val SESSION_LOGS_SEARCH = "sessionLogsSearch"
    const val SESSION_LOGS_EXPORT = "sessionLogsExport"

    override val definitions = listOf(
        ToolDefinition(name = HEALTH_CHECK, description = "Check device health: battery, storage, memory, security status.", category = "system", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = DEVICE_INFO, description = "Get detailed device information: model, manufacturer, Android version, etc.", category = "system", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SESSION_LOGS_SEARCH, description = "Search app session logs for specific events or patterns.", category = "system", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query (regex supported)"), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results to return")), permissions = emptyList()),
        ToolDefinition(name = SESSION_LOGS_EXPORT, description = "Export session logs to a file.", category = "system", parameters = listOf(ToolParameter("format", ToolParameterType.String, false, "Export format (json, csv, txt)"), ToolParameter("startDate", ToolParameterType.String, false, "Date range start (ISO format, optional)"), ToolParameter("endDate", ToolParameterType.String, false, "Date range end (ISO format, optional)")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = SystemToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}