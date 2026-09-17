package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.web.WebFetchService
import com.unuslumen.app.data.web.WebSearchService
import kotlinx.serialization.json.Json

class WebToolExecutor(
    private val searchService: WebSearchService,
    private val fetchService: WebFetchService
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            WebToolDefinitions.WEB_SEARCH -> webSearch(args)
            WebToolDefinitions.WEB_FETCH -> webFetch(args)
            WebToolDefinitions.WEB_SUMMARIZE -> webSummarize(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun webSearch(args: Map<String, Any?>): ToolExecutionResult {
        val query = args["query"] as? String
            ?: return ToolExecutionResult.error("Missing 'query' parameter")

        val results = searchService.search(query)
        if (results.isEmpty()) {
            val res = WebSearchResult(emptyList(), "No results found for: $query. All search engines failed (SearXNG, Brave, Ahmia, Torch). Tor circuits may need refresh.")
            return ToolExecutionResult.success(res, json.encodeToString(WebSearchResult.serializer(), res))
        }
        val res = WebSearchResult(results.map { WebSearchEntry(it.title, it.url, it.snippet) }, null)
        return ToolExecutionResult.success(res, json.encodeToString(WebSearchResult.serializer(), res))
    }

    private suspend fun webFetch(args: Map<String, Any?>): ToolExecutionResult {
        val url = args["url"] as? String
            ?: return ToolExecutionResult.error("Missing 'url' parameter")

        val result = fetchService.fetch(url)
        val res = WebFetchResult(result.url, result.title, result.content, result.success, result.error)
        return ToolExecutionResult.success(res, json.encodeToString(WebFetchResult.serializer(), res))
    }

    private suspend fun webSummarize(args: Map<String, Any?>): ToolExecutionResult {
        val url = args["url"] as? String
            ?: return ToolExecutionResult.error("Missing 'url' parameter")

        val result = fetchService.fetch(url, maxLength = 20000)
        val res = WebFetchResult(result.url, result.title, result.content, result.success, result.error)
        return ToolExecutionResult.success(res, json.encodeToString(WebFetchResult.serializer(), res))
    }
}