// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * What an agent shows its owner, category by category.
 *
 * WHY THIS IS ELEVEN SETTINGS AND NOT A BOOLEAN. The first proposal reduced transparency to
 * an OFF/FULL switch, and that was rejected because it is not a control, it is a mood. A
 * person who wants every tool call and result but only the gist of a decision has no way to
 * say so with one switch, and the switch teaches them nothing about what they are choosing.
 *
 * CAPTURE AND DISPLAY ARE SEPARATE, and that separation is the whole reason this type has
 * two fields per category rather than one. A person may hide reasoning from the room
 * without destroying the record of it, and may show reasoning captured before they turned
 * display on. Conflating the two means hiding something deletes it, which nobody asked for.
 *
 * THE DEFAULT IS TRANSPARENT. Every category is captured and shown unless the owner says
 * otherwise, because an agent nobody can see is an agent nobody can trust, and the person
 * who needs to hide things is the exception rather than the rule.
 */
@Serializable
data class AgentVisibility(
    val plans: VisibilityRule = VisibilityRule.DEFAULT,
    val activity: VisibilityRule = VisibilityRule.DEFAULT,
    val reasoning: VisibilityRule = VisibilityRule.DEFAULT,
    val decisions: VisibilityRule = VisibilityRule.DEFAULT,
    val tools: VisibilityRule = VisibilityRule.DEFAULT,
    val context: VisibilityRule = VisibilityRule.DEFAULT,
    val memory: VisibilityRule = VisibilityRule.DEFAULT,
    val communications: VisibilityRule = VisibilityRule.DEFAULT,
    val verification: VisibilityRule = VisibilityRule.DEFAULT,
    val errors: VisibilityRule = VisibilityRule.DEFAULT,
    val outputs: VisibilityRule = VisibilityRule.DEFAULT,
) {
    /** Every category with its rule, so a surface can iterate rather than hardcode eleven. */
    fun asMap(): Map<VisibilityCategory, VisibilityRule> = mapOf(
        VisibilityCategory.PLANS to plans,
        VisibilityCategory.ACTIVITY to activity,
        VisibilityCategory.REASONING to reasoning,
        VisibilityCategory.DECISIONS to decisions,
        VisibilityCategory.TOOLS to tools,
        VisibilityCategory.CONTEXT to context,
        VisibilityCategory.MEMORY to memory,
        VisibilityCategory.COMMUNICATIONS to communications,
        VisibilityCategory.VERIFICATION to verification,
        VisibilityCategory.ERRORS to errors,
        VisibilityCategory.OUTPUTS to outputs,
    )

    fun ruleFor(category: VisibilityCategory): VisibilityRule = asMap().getValue(category)

    companion object {
        /** Everything shown, everything captured. The default an agent is created with. */
        val TRANSPARENT = AgentVisibility()
    }
}

/**
 * One category's rule: whether it is captured, whether it is shown, and how much detail.
 *
 * [capture] is what the runtime records. [display] is what the room draws. They are
 * independent on purpose: turning display off must never stop the record, because a person
 * who hides a category today may want it tomorrow and the past cannot be re-captured.
 */
@Serializable
data class VisibilityRule(
    val capture: Boolean = true,
    val display: Boolean = true,
    val detail: VisibilityDetail = VisibilityDetail.FULL,
) {
    companion object {
        val DEFAULT = VisibilityRule()

        /** Captured and recorded, never drawn. The record survives the preference. */
        val CAPTURE_ONLY = VisibilityRule(capture = true, display = false)

        /** Nothing recorded and nothing drawn. The only setting that loses information. */
        val OFF = VisibilityRule(capture = false, display = false, detail = VisibilityDetail.SUMMARY)
    }
}

/** How much of a category is shown when it is shown at all. */
@Serializable
enum class VisibilityDetail {
    /** One line: that it happened. */
    SUMMARY,

    /** The shape of it without the payloads. */
    STANDARD,

    /** Everything the runtime has, including arguments and results. */
    FULL,
}

/** The eleven categories from the specification, as a type rather than a comment. */
enum class VisibilityCategory(val label: String, val explanation: String) {
    PLANS("Plans", "The plan the agent formed, and every revision of it."),
    ACTIVITY("Activity", "What the agent is doing right now."),
    REASONING("Reasoning", "The model's own reasoning channel, where the provider exposes one."),
    DECISIONS("Decisions", "Decisions taken, the alternatives considered and why."),
    TOOLS("Tools", "Which tools ran, with what arguments, and what came back."),
    CONTEXT("Context", "What information was retrieved and given to the model."),
    MEMORY("Memory", "What the agent read from or wrote to memory."),
    COMMUNICATIONS("Communications", "Exchanges between the agent, GURU and you."),
    VERIFICATION("Verification", "Checks the agent performed and what they found."),
    ERRORS("Errors", "Failures, retries, refusals and limitations."),
    OUTPUTS("Outputs", "What the agent produced, intermediate and final."),
}
