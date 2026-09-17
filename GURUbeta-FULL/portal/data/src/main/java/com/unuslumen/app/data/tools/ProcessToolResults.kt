package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class ProcessInfo(
    val pid: String,
    val name: String,
    val state: String
) : ToolResultData

@Serializable
data class ProcessListResult(
    val processes: List<ProcessInfo>,
    val error: String?
) : ToolResultData

@Serializable
data class ProcessActionResult(
    val success: Boolean,
    val error: String?
) : ToolResultData