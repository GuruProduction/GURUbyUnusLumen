package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class WebBrowserLoadResult(val url: String, val title: String, val content: String, val html: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserContentResult(val content: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserScreenshotResult(val base64: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserActionResult(val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserConsoleResult(val messages: List<String>, val count: Int, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserNetworkResult(val requests: List<String>, val count: Int, val success: Boolean, val error: String?) : ToolResultData