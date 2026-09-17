package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class ToolResultEntry(
    val toolName: String,
    val parameters: String,
    val result: String,
    val timestamp: Long,
    val ageMinutes: Long,
    val isStale: Boolean
) : ToolResultData

@Serializable
data class ToolResultRetrievalResult(
    val results: List<ToolResultEntry>
) : ToolResultData