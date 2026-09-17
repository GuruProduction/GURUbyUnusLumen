package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class AppInfo(val packageName: String, val name: String, val versionName: String, val isSystem: Boolean) : ToolResultData
@Serializable data class AppListResult(val apps: List<AppInfo>, val error: String?) : ToolResultData
@Serializable data class AppActionResult(val success: Boolean, val error: String?) : ToolResultData