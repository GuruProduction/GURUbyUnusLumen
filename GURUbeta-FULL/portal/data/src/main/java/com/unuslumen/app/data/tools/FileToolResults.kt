package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class FileSearchResult(
    val files: List<String>,
    val error: String?
) : ToolResultData

@Serializable
data class FileEditResult(
    val success: Boolean,
    val path: String,
    val error: String?
) : ToolResultData