package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class WidgetResult(val success: Boolean, val error: String?) : ToolResultData
@Serializable
data class GuruShortcutInfo(val id: String, val shortLabel: String, val longLabel: String, val isPinned: Boolean) : ToolResultData
@Serializable
data class WidgetListResult(val shortcuts: List<GuruShortcutInfo>, val error: String?) : ToolResultData