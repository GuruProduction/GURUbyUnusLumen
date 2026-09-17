package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class ClipboardResult(
    val text: String,
    val hasText: Boolean,
    val error: String?
) : ToolResultData