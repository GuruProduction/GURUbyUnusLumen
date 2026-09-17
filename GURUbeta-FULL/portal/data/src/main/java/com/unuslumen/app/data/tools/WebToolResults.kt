package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class WebSearchResult(
    val results: List<WebSearchEntry>,
    val error: String?
) : ToolResultData

@Serializable
data class WebSearchEntry(
    val title: String,
    val url: String,
    val snippet: String
) : ToolResultData

@Serializable
data class WebFetchResult(
    val url: String,
    val title: String,
    val content: String,
    val success: Boolean,
    val error: String?
) : ToolResultData