package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LuxifySkill(
    val id: String,
    val name: String,
    val description: String,
    val whenToUse: String = "",
    val allowedTools: List<String> = emptyList(),
    val bodyMarkdown: String,
    val source: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CreateLuxifyRequest(
    val name: String,
    val description: String,
    val whenToUse: String,
    val allowedTools: List<String>,
    val bodyMarkdown: String
)

@Serializable
data class LuxifySummary(
    val totalSkills: Int,
    val enabledSkills: Int,
    val bundledSkills: Int,
    val dynamicSkills: Int = 0,
    val userSkills: Int
)