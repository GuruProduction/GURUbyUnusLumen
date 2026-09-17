package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class ScreenShotResult(
    val success: Boolean,
    val path: String,
    val sizeBytes: Long,
    val error: String?
) : ToolResultData