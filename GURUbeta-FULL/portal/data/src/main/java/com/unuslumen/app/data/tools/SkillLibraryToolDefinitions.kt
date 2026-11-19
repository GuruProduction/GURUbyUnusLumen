// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * The skills-library tool set — the numen's window into the Unus Lumen
 * publisher's evergrowing content catalog.
 *
 * The numen carries bundled skills from birth, and beyond them an
 * evergrowing library it can browse, search, inspect, and install from —
 * permanently, into its own persistent skill store, growing its own
 * capability set with its human's needs over time. Ottio's marketplace
 * rides the same backbone later; these verbs are the consumer half.
 */
object SkillLibraryToolDefinitions : ToolSetRegistration {
    const val BROWSE_SKILL_LIBRARY = "browseSkillLibrary"
    const val SEARCH_SKILL_LIBRARY = "searchSkillLibrary"
    const val GET_SKILL_FROM_LIBRARY = "getSkillFromLibrary"
    const val INSTALL_SKILL_FROM_LIBRARY = "installSkillFromLibrary"

    // THE AUTHORING VERBS (issue 492).
    //
    // The six names above could use a skill and install someone else's, and none of
    // them could write one. The Self Improvement Engine's remit names skills as part
    // of development, and the repository underneath already had create, update,
    // enable, disable and delete; only the tools were missing.
    const val CREATE_SKILL = "createSkill"
    const val UPDATE_SKILL = "updateSkill"
    const val LIST_SKILLS = "listSkills"
    const val ENABLE_SKILL = "enableSkill"
    const val DISABLE_SKILL = "disableSkill"

    override val definitions = listOf(
        ToolDefinition(
            name = BROWSE_SKILL_LIBRARY,
            description = "Browse the Unus Lumen skill library — every skill currently published in the central catalog, with installed / update-available status. Use to discover capability beyond your bundled skills.",
            category = "skills",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SEARCH_SKILL_LIBRARY,
            description = "Search the Unus Lumen skill library by keyword, matching against name and slug. Returns matching catalog entries with versions and install status.",
            category = "skills",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.String, required = true, description = "Search keyword(s) for the library")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GET_SKILL_FROM_LIBRARY,
            description = "Read a library skill's full body (methodology, steps, allowed tools) WITHOUT installing it. Use for evalutation— decide whether to install.",
            category = "skills",
            parameters = listOf(
                ToolParameter("slug", ToolParameterType.String, required = true, description = "The skill's slug from the catalog listing")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = INSTALL_SKILL_FROM_LIBRARY,
            description = "Install (or update) a library skill permanently INTO your own skills store. After install, it appears in your normal listing alongside bundled skills and stays put across reboots.",
            category = "skills",
            parameters = listOf(
                ToolParameter("slug", ToolParameterType.String, required = true, description = "The skill's slug from the catalog listing")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = LIST_SKILLS,
            description = "List the skills you hold, bundled, installed and ones you have written yourself, with their names, descriptions, when-to-use lines and enabled state. Read this BEFORE writing a new skill, so you extend what exists rather than duplicating it.",
            category = "skills",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = CREATE_SKILL,
            description = "Write a new skill and store it permanently. A skill is a methodology you can invoke later: the body is the instruction set that expands into your context when the skill runs, so write the steps a future run would need rather than a description of them. Refused if the name is unusable, the description or body is missing, the body is too short to be a methodology, or an allowed tool does not exist.",
            category = "skills",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, required = true, description = "The skill's name: letters, digits, dashes and spaces"),
                ToolParameter("description", ToolParameterType.String, required = true, description = "One line saying what the skill does, for the listing"),
                ToolParameter("whenToUse", ToolParameterType.String, required = false, description = "When this skill should fire on its own, phrased as the words a person would type"),
                ToolParameter("allowedTools", ToolParameterType.String, required = false, description = "Comma-separated tool names the skill may call. Omit for none"),
                ToolParameter("bodyMarkdown", ToolParameterType.String, required = true, description = "The full instruction set, in markdown"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = UPDATE_SKILL,
            description = "Change a skill you have written: its description, when-to-use line, allowed tools or body. The previous version is replaced in place, so read it first with listSkills if you need to preserve wording.",
            category = "skills",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, required = true, description = "The skill's name, as it appears in your listing"),
                ToolParameter("description", ToolParameterType.String, required = false, description = "New one-line description"),
                ToolParameter("whenToUse", ToolParameterType.String, required = false, description = "New when-to-use line"),
                ToolParameter("allowedTools", ToolParameterType.String, required = false, description = "New comma-separated tool names, replacing the old set"),
                ToolParameter("bodyMarkdown", ToolParameterType.String, required = false, description = "New instruction body"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = ENABLE_SKILL,
            description = "Turn a disabled skill back on, without destroying it.",
            category = "skills",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, required = true, description = "The skill's name"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = DISABLE_SKILL,
            description = "Turn a skill off WITHOUT deleting it, so its text survives and it can be turned back on. Use this rather than deleting when a methodology has gone stale or is doing harm.",
            category = "skills",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, required = true, description = "The skill's name"),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = SkillLibraryToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}