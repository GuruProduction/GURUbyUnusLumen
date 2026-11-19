// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/** One readable section of the assembled system prompt (issue 492). */
@Serializable
data class PromptSectionEntry(
    val id: String,
    val heading: String,
    val startOffset: Int,
    val length: Int,
) : ToolResultData

@Serializable
data class PromptSectionListResult(
    val total: Int,
    val sections: List<PromptSectionEntry>,
) : ToolResultData

@Serializable
data class PromptSectionReadResult(
    val success: Boolean,
    val sectionId: String,
    val text: String,
    val error: String? = null,
) : ToolResultData

/**
 * A proposed change to the agent's own instructions.
 *
 * `applied` is a field rather than an implication, and it is always false from this
 * device. A proposal carries the text it would replace so a human reading it does not
 * have to reconstruct what changed.
 */
@Serializable
data class PromptEditProposalResult(
    val success: Boolean,
    val sectionId: String,
    val currentText: String,
    val error: String? = null,
    val proposedText: String = "",
    val rationale: String = "",
    val applied: Boolean = false,
    val note: String = "",
) : ToolResultData
