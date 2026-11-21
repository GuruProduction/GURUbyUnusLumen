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
 * The verb an agent uses to say what it decided, and why.
 *
 * WHY THIS IS A TOOL RATHER THAN A PARSED PARAGRAPH. The specification is explicit that a structured
 * decision record and a model's raw reasoning channel are different kinds of information and must be
 * labelled as such. Reasoning is whatever the provider chose to emit; it is absent entirely on a
 * model with no reasoning channel. A decision is a fact the agent deliberately writes, so it exists
 * on every model, and it carries named fields rather than prose a reader has to interpret.
 *
 * IT DOES NOT WRITE A NOTE OR TOUCH THE DEVICE. The record it produces is an event in the run's
 * ledger, which is the same place every other fact about the run lives. A decision kept as a note
 * would be a document outside the execution history, and the history is what proves what happened.
 */
object DecisionToolDefinitions : ToolSetRegistration {

    const val RECORD_DECISION = "recordDecision"

    override val definitions = listOf(
        ToolDefinition(
            name = RECORD_DECISION,
            description = "Record a decision you have made, with the reasons, so the person can see " +
                "your thinking rather than only your conclusion. Use it when you choose an approach, " +
                "change direction, reject an option, or reach a conclusion that matters to the job. " +
                "Every field is your own words and short is better than thorough: one clear line per " +
                "field tells a reader more than a paragraph. Do not use it to narrate progress; a " +
                "decision is a fork you took, not a step you completed. Recording is cheap, so record " +
                "the ones you would want explained to you if you were reading someone else's work.",
            category = "planning",
            parameters = listOf(
                ToolParameter(
                    name = "decision",
                    type = ToolParameterType.String,
                    required = true,
                    description = "What you decided, in one sentence.",
                ),
                ToolParameter(
                    name = "problem",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The problem as you understood it when you decided this. Another " +
                        "reader will not have your context, so state it rather than assuming it.",
                ),
                ToolParameter(
                    name = "approach",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The approach you chose.",
                ),
                ToolParameter(
                    name = "alternatives",
                    type = ToolParameterType.String,
                    required = false,
                    description = "What you considered and did not choose, separated by a vertical " +
                        "bar. Leave it out if there was genuinely nothing else rather than " +
                        "inventing one to look thorough.",
                ),
                ToolParameter(
                    name = "reason",
                    type = ToolParameterType.String,
                    required = true,
                    description = "Why this approach rather than the others. This is the field that " +
                        "makes the record worth keeping.",
                ),
                ToolParameter(
                    name = "evidence",
                    type = ToolParameterType.String,
                    required = false,
                    description = "What you saw that supports this: a tool result, a source, a " +
                        "measurement. Name where it came from so it can be checked.",
                ),
                ToolParameter(
                    name = "uncertainty",
                    type = ToolParameterType.String,
                    required = false,
                    description = "What you are still unsure about. Leaving this out when you are " +
                        "unsure makes the record read more confident than the work was.",
                ),
                ToolParameter(
                    name = "next",
                    type = ToolParameterType.String,
                    required = false,
                    description = "What you intend to do as a result.",
                ),
            ),
            permissions = emptyList(),
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = DecisionToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
