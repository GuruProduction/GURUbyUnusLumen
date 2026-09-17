package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class FileProcessResult(
    val success: Boolean,
    val path: String,
    val fileName: String,
    val fileType: String,
    val extractedText: String,
    val metadata: Map<String, String>,
    val error: String?
) : ToolResultData