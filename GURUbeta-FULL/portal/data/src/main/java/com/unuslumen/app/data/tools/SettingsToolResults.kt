package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class PreferenceResult(val key: String, val value: String) : ToolResultData
@Serializable
data class PreferenceEntry(val key: String, val value: String, val description: String) : ToolResultData
@Serializable
data class AllPreferencesResult(val preferences: List<PreferenceEntry>) : ToolResultData