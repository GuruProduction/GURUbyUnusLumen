package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable
data class UseSkillResult(
    val success: Boolean,
    val skillName: String,
    val description: String = "",
    val whenToUse: String = "",
    val allowedTools: List<String> = emptyList(),
    val bodyMarkdown: String,
    val error: String? = null
) : ToolResultData

@Serializable
data class SkillSearchEntry(
    val name: String,
    val description: String,
    val whenToUse: String,
    val source: String,
    val enabled: Boolean
) : ToolResultData

@Serializable
data class SearchSkillsResult(
    val query: String,
    val totalMatches: Int,
    val skills: List<SkillSearchEntry>
) : ToolResultData

@Serializable
data class SkillListingEntry(
    val name: String,
    val description: String,
    val whenToUse: String
)