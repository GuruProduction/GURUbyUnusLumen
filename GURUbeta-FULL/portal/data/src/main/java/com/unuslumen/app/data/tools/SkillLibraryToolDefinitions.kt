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
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = SkillLibraryToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}