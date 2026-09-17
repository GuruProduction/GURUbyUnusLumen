package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ToolResultToolDefinitions : ToolSetRegistration {

    const val GET_TOOL_RESULT = "getToolResult"
    const val SEARCH_TOOL_RESULTS = "searchToolResults"

    override val definitions = listOf(
        ToolDefinition(
            name = GET_TOOL_RESULT,
            description = "Retrieve past tool results by tool name without re-calling the tool. Every tool result is permanently stored in your brain. Use this when a result has been cleared from your context and you need it back. Saves compute by avoiding re-calling tools you already ran. Volatile tools (weather, notifications) have TTLs. Expired results return with isStale=true and a note suggesting you re-call for fresh data. Stable tools (notes, tasks, device info) can be retrieved safely hours or days later.",
            category = "tool_result",
            parameters = listOf(
                ToolParameter("toolName", ToolParameterType.String, required = true, description = "The name of the tool whose results you want to retrieve, e.g. 'getAllTasks' or 'weatherCurrent'"),
                ToolParameter("limit", ToolParameterType.Integer, required = false, description = "Maximum number of results to return, default 5"),
                ToolParameter("conversationId", ToolParameterType.String, required = false, description = "Optional conversation ID to scope results to a specific conversation. If omitted, searches across all conversations.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SEARCH_TOOL_RESULTS,
            description = "Search all past tool results by content using full-text search. Use this when you need a past result but don't remember which tool produced it, or when you want to find results that mention specific keywords. Results are ranked by text relevance. Expired volatile results return with isStale=true and a note suggesting re-call.",
            category = "tool_result",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.String, required = true, description = "What to search for in past tool results. Matches against the content of the results."),
                ToolParameter("limit", ToolParameterType.Integer, required = false, description = "Maximum number of results to return, default 5")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = ToolResultToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = ToolResultToolResultExtractor::class
}