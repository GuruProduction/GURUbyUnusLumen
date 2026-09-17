package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.domain.model.WebSearchItem
import kotlin.reflect.KClass

object WebToolDefinitions : ToolSetRegistration {

    const val WEB_SEARCH = "webSearch"
    const val WEB_FETCH = "webFetch"
    const val WEB_SUMMARIZE = "webSummarize"

    override val definitions = listOf(
        ToolDefinition(
            name = WEB_SEARCH,
            description = "Search the web through Tor for complete privacy. Uses SearXNG (metasearch aggregating multiple engines) as primary, Brave onion, Ahmia, and Torch as fallbacks. All traffic routed through Tor. No API keys, no tracking. Returns results with titles, URLs, snippets, and which engine was used.",
            category = "web",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.String, required = true, description = "What to search for")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = WEB_FETCH,
            description = "Fetch the content of a web page through Tor. Returns the page title and text content. Use this to read articles, documentation, or any web page privately.",
            category = "web",
            parameters = listOf(
                ToolParameter("url", ToolParameterType.String, required = true, description = "The URL to fetch")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = WEB_SUMMARIZE,
            description = "Fetch a web page through Tor and return the full content for summarization. Private browsing, no tracking.",
            category = "web",
            parameters = listOf(
                ToolParameter("url", ToolParameterType.String, required = true, description = "The URL to fetch and summarize")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = WebToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = WebToolResultExtractor::class
}