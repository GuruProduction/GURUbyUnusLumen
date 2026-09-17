package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class HttpResponseResult(val statusCode: Int, val statusText: String, val headers: String, val body: String, val success: Boolean, val error: String?) : ToolResultData
@Serializable data class HttpDownloadResult(val success: Boolean, val path: String, val sizeBytes: Long, val contentType: String, val error: String?) : ToolResultData
@Serializable data class ResolveDownloadResult(val success: Boolean, val resolvedUrl: String?, val pageTitle: String?, val error: String?) : ToolResultData