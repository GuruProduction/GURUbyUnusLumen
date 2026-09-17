package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class IntentResult(val success: Boolean, val error: String?) : ToolResultData
@Serializable data class IntentQueryResult(val packages: List<String>, val error: String?) : ToolResultData