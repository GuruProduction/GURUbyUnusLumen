package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class AskUserResult(
    val question: String,
    val options: List<String>,
    val message: String
) : ToolResultData

@Serializable
data class NotificationResult(
    val success: Boolean,
    val error: String?
) : ToolResultData