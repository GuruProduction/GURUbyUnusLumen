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
 * The verb an agent uses to record what it checked, and how far the check went.
 *
 * WHY A COMPLETION CLAIM NEEDS ONE. Section 4.4 asks that a claim the work is done be supported by
 * execution evidence, and that an attempt, an acceptance and a real-world confirmation be three
 * distinguishable things. A report is prose: "the message was sent" and "the message was accepted by
 * the gateway and I did not confirm it arrived" are the same sentence to a reader skimming, and only
 * one of them is a claim anybody should act on.
 *
 * SO THIS RECORDS THE THREE LEVELS EXPLICITLY. Attempted means the action was taken and its effect
 * unchecked. Accepted means the receiving mechanism acknowledged it. Confirmed means the effect was
 * observed independently of the thing that produced it. Only the last supports reporting the work as
 * done, and the tool says so rather than trusting the agent to remember.
 */
object VerificationToolDefinitions : ToolSetRegistration {

    const val RECORD_VERIFICATION = "recordVerification"

    override val definitions = listOf(
        ToolDefinition(
            name = RECORD_VERIFICATION,
            description = "Record what you checked and how far the check got, before you claim " +
                "something is done. Three levels, and they are not the same thing: ATTEMPTED means " +
                "you did the thing and have not checked the effect; ACCEPTED means the mechanism " +
                "that received it acknowledged it, such as a command exiting zero or an API " +
                "answering; CONFIRMED means you observed the effect independently, such as reading " +
                "the file back or querying the row. A claim that the work is finished needs " +
                "CONFIRMED. Name the evidence: where you looked and what you saw. If something is " +
                "still unchecked, say so here rather than leaving it for the person to discover.",
            category = "planning",
            parameters = listOf(
                ToolParameter(
                    name = "claim",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The thing you are asserting, in one sentence: that the config " +
                        "file is updated, rather than a label for the area of work.",
                ),
                ToolParameter(
                    name = "level",
                    type = ToolParameterType.String,
                    required = true,
                    description = "attempted, accepted or confirmed. Choosing a higher level than " +
                        "your evidence supports is worse than choosing none, because it tells the " +
                        "person a check happened that did not.",
                ),
                ToolParameter(
                    name = "evidence",
                    type = ToolParameterType.String,
                    required = true,
                    description = "What you observed and where: the output, the path you read, the " +
                        "row you queried. A confirmation with no source is an assertion with a " +
                        "longer word.",
                ),
                ToolParameter(
                    name = "check",
                    type = ToolParameterType.String,
                    required = false,
                    description = "What you did to check it, so the check can be repeated: that you " +
                        "read the file back after writing, rather than the word verified.",
                ),
                ToolParameter(
                    name = "uncertainty",
                    type = ToolParameterType.String,
                    required = false,
                    description = "What remains unchecked or uncertain. Leaving this out when " +
                        "something is open makes the record read more finished than the work is.",
                ),
            ),
            permissions = emptyList(),
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = VerificationToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
