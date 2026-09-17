package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.repository.LuxifyRepository
import kotlinx.serialization.json.Json

class LuxifyToolExecutor(
    private val luxifyRepository: LuxifyRepository
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            LuxifyToolDefinitions.USE_SKILL -> useSkill(args)
            LuxifyToolDefinitions.SEARCH_SKILLS -> searchSkills(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun useSkill(args: Map<String, Any?>): ToolExecutionResult {
        val skillName = args["skillName"] as? String
            ?: return ToolExecutionResult.error("Missing 'skillName' parameter")

        val skill = luxifyRepository.getSkillByName(skillName)
            ?: return run {
                val result = UseSkillResult(false, skillName, "", "", emptyList(), "", "No skill found with name '$skillName'. Check the skill listing in your system prompt for available skill names.")
                ToolExecutionResult.success(result, json.encodeToString(UseSkillResult.serializer(), result))
            }

        val result = UseSkillResult(true, skill.name, skill.description, skill.whenToUse, skill.allowedTools, skill.bodyMarkdown, null)
        return ToolExecutionResult.success(result, json.encodeToString(UseSkillResult.serializer(), result))
    }

    private suspend fun searchSkills(args: Map<String, Any?>): ToolExecutionResult {
        val query = args["query"] as? String ?: ""
        val allSkills = luxifyRepository.getAllSkills()
        val searchTerm = query.trim().lowercase()

        val matching = if (searchTerm.isEmpty()) {
            allSkills
        } else {
            allSkills.filter { skill ->
                skill.name.lowercase().contains(searchTerm) ||
                    skill.description.lowercase().contains(searchTerm) ||
                    skill.whenToUse.lowercase().contains(searchTerm)
            }
        }

        val result = SearchSkillsResult(query, matching.size, matching.map { skill ->
            SkillSearchEntry(skill.name, skill.description, skill.whenToUse, skill.source, skill.enabled)
        })
        return ToolExecutionResult.success(result, json.encodeToString(SearchSkillsResult.serializer(), result))
    }
}