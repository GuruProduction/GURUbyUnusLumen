// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class WebBrowserLoadResult(val url: String, val title: String, val content: String, val html: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserContentResult(val content: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserScreenshotResult(val base64: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserActionResult(val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserConsoleResult(val messages: List<String>, val count: Int, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class WebBrowserNetworkResult(val requests: List<String>, val count: Int, val success: Boolean, val error: String?) : ToolResultData