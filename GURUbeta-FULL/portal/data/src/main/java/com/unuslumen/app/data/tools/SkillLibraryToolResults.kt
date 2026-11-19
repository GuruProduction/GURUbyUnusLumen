// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/**
 * Results for the skill-library tool set. SkillForge's transport shapes,
 * handed to the numen as typed JSON like every other result.
 */

@Serializable
data class LibraryBrowseEntry(
    val slug: String,
    val name: String,
    val version: Int,
    val installed: Boolean,
    val installedVersion: Int? = null,
    val updateAvailable: Boolean = false
) : ToolResultData

@Serializable
data class BrowseLibraryResult(
    val reachable: Boolean,
    val totalInLibrary: Int,
    val skills: List<LibraryBrowseEntry>,
    val error: String? = null
) : ToolResultData

@Serializable
data class LibrarySearchEntry(
    val slug: String,
    val name: String,
    val whenToUse: String,
    val version: Int,
    val installed: Boolean,
    val updateAvailable: Boolean = false
) : ToolResultData

@Serializable
data class SearchLibraryResult(
    val query: String,
    val reachable: Boolean,
    val totalMatches: Int,
    val skills: List<LibrarySearchEntry>,
    val error: String? = null
) : ToolResultData

@Serializable
data class GetSkillLibraryResult(
    val success: Boolean,
    val slug: String,
    val name: String = "",
    val description: String = "",
    val whenToUse: String = "",
    val allowedTools: List<String> = emptyList(),
    val bodyMarkdown: String = "",
    val version: Int = 0,
    val error: String? = null
) : ToolResultData

@Serializable
data class InstallSkillLibraryResult(
    val success: Boolean,
    val slug: String,
    val name: String = "",
    val version: Int = 0,
    val installed: Boolean = false,
    val wasUpdate: Boolean = false,
    val message: String = "",
    val error: String? = null
) : ToolResultData

/**
 * One row in the agent's own skill listing (issue 492).
 *
 * `bodyChars` is the body's length rather than the body itself: a listing has to be
 * small enough to read at a glance, and a caller that wants the text reads the skill
 * it names. `allowedTools` is here because the grants are what decide whether a
 * methodology can run, and a listing that hid them would show a skill that looks
 * able to do things it cannot.
 */
@Serializable
data class SkillAuthoringEntry(
    val name: String,
    val description: String,
    val whenToUse: String,
    val source: String,
    val enabled: Boolean,
    val bodyChars: Int,
    val allowedTools: List<String> = emptyList(),
) : ToolResultData

@Serializable
data class SkillAuthoringListResult(
    val total: Int,
    val skills: List<SkillAuthoringEntry>,
) : ToolResultData

/**
 * The outcome of a write to the skill store.
 *
 * A refusal carries the sentence that says why and what to do instead, because a
 * write that silently did nothing is the class of defect this whole issue is about.
 */
@Serializable
data class SkillAuthoringWriteResult(
    val success: Boolean,
    val name: String,
    val createdAt: Long = 0L,
    val error: String? = null,
) : ToolResultData