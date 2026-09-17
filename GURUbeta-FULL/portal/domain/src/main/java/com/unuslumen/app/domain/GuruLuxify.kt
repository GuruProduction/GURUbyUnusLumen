package com.unuslumen.app.domain

import com.unuslumen.app.domain.model.LuxifySkill

object GuruLuxify {

    fun fullLuxifySection(activeSkills: List<LuxifySkill>): String {
        if (activeSkills.isEmpty()) return ""

        val skillListing = activeSkills.joinToString("\n") { skill ->
            buildString {
                append("- ${skill.name}: ${skill.description}")
                if (skill.whenToUse.isNotBlank()) {
                    append(" (Use when: ${skill.whenToUse})")
                }
            }
        }

        return """
# Skills

You have skills available. Skills are permanent capabilities that give you methodology, standards, and expertise for specific domains. Each skill is listed below with its name, description, and trigger conditions.

## How to use skills

1. When the user's request matches what a skill covers, call the useSkill tool with the skill name to load its full methodology.
2. The useSkill tool returns the skill's complete instructions, including steps and success criteria.
3. Follow the skill's methodology to complete the task.
4. Skills are not static text. They are processes you execute. Load them, follow them, deliver the result.

## Discovering skills

1. If you are not sure whether a skill exists for a task, call the searchSkills tool with a keyword related to what you need.
2. The searchSkills tool searches across skill names, descriptions, and trigger conditions from bundled, dynamic, and user-created skills.
3. Pass an empty string to searchSkills to get all available skills.
4. Always search before starting a task if you are unsure whether a skill covers it.

## Available skills

$skillListing

## Rules

- Search the listing above before starting any task. If a skill covers what you're about to do, load it first.
- If no skill in the listing matches but you suspect one might exist, use searchSkills to check.
- Do not skip the skill search. Working without a skill when one exists means working without methodology.
- When multiple skills could apply, pick the most specific one.
- Skills compound. The more you use them, the better your work becomes.
""".trimIndent()
    }
}
