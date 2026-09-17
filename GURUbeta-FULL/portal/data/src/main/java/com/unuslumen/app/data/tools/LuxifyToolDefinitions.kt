package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object LuxifyToolDefinitions : ToolSetRegistration {

    const val USE_SKILL = "useSkill"
    const val SEARCH_SKILLS = "searchSkills"

    override val definitions = listOf(
        ToolDefinition(
            name = USE_SKILL,
            description = "Load and activate a skill by name. Returns the full skill methodology including steps, success criteria, and instructions. Call this before starting any task that matches a skill in your listing. The returned content is the complete process you should follow.",
            category = "luxify",
            parameters = listOf(
                ToolParameter("skillName", ToolParameterType.String, required = true, description = "The name of the skill to load, exactly as it appears in the skill listing in your system prompt")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SEARCH_SKILLS,
            description = "Search available skills by keyword. Returns matching skills with their names, descriptions, and trigger conditions. Searches across skill names, descriptions, and when-to-use fields from bundled, dynamic, and user-created skills. Use this to discover what skills you have before starting a task. Pass an empty string to get all skills.",
            category = "luxify",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.String, required = true, description = "Search query. Matches against skill name, description, and when_to_use. Pass empty string to list all.")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = LuxifyToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = LuxifyToolResultExtractor::class
}