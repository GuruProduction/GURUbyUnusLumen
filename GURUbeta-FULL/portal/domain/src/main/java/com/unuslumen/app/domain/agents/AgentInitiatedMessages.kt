// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * The kinds of message an agent may start.
 *
 * WHY THIS IS A RULE RATHER THAN A STRING THE MODEL PICKS. Section 5.3 asks an agent to initiate
 * communication for clarification, findings, blockage, approval, risk, milestone and review. Until
 * this existed, `askAssistant` was the only door and everything arrived as a clarification, which
 * erases the difference between a question and a finding. A finding does not need an answer; a
 * blocker does; a risk is a warning the person may act on whenever. Reporting all of them as one
 * type leaves a room unable to draw them differently and a notification unable to decide whether it
 * is worth waking anybody.
 *
 * TWO SHAPES, AND THE DIFFERENCE IS WHETHER THE RUN STOPS.
 *
 *  - A QUESTION pauses the run: the agent cannot continue honestly without the answer, so it stops
 *    and hands the question out. That is `askAssistant`, and it is unchanged.
 *  - A REPORT pauses nothing: the agent keeps working and the person finds the message in the room
 *    when they look. A finding that halted the work, or a risk that blocked it, would make an agent
 *    stop for things it should simply mention.
 *
 * THE SET IS CLOSED ON PURPOSE. The broker's vocabulary covers both directions; an agent starting a
 * conversation with the person has six that mean something, and a seventh invented by a model would
 * be a type no surface knows how to draw.
 */
object AgentInitiatedMessages {

    /** Stopped and waiting on an answer. The only shape that pauses the run. */
    const val CLARIFICATION = AgentMessageTypes.CLARIFICATION

    /** Something it worked out that changes what the person would do. No answer needed. */
    const val FINDING = AgentMessageTypes.FINDING

    /** It cannot proceed without something it does not have. */
    const val BLOCKER = AgentMessageTypes.BLOCKER

    /** A stage of the work is finished and worth reporting before the whole job is. */
    const val MILESTONE = AgentMessageTypes.MILESTONE

    /** Something it noticed that could go wrong, which the person may want to weigh. */
    const val RISK = AgentMessageTypes.RISK

    /** It wants the person to look at what it produced before it builds on it. */
    const val REVIEW_REQUEST = AgentMessageTypes.REVIEW_REQUEST

    /**
     * The types an agent may start, and what each one says it is for.
     *
     * THE DESCRIPTION IS THE CONTRACT WITH THE MODEL. It is what the tool parameter offers, so it is
     * written as a rule the model can apply rather than as a label, because a model choosing between
     * a FINDING and a RISK needs to know which one it has.
     */
    val OFFERED: List<Option> = listOf(
        Option(
            type = CLARIFICATION,
            value = "clarification",
            description = "You cannot continue without knowing something, so the run stops and " +
                "waits for an answer. Use this only when the answer changes what you do next.",
            pauses = true,
        ),
        Option(
            type = FINDING,
            value = "finding",
            description = "You worked something out that the person would want to know. The run " +
                "carries on; the message is there when they look.",
            pauses = false,
        ),
        Option(
            type = BLOCKER,
            value = "blocker",
            description = "Something is in the way that you cannot get past with the tools you " +
                "have. Say what it is and what would clear it.",
            pauses = false,
        ),
        Option(
            type = MILESTONE,
            value = "milestone",
            description = "A stage of the work is finished and worth reporting before the whole " +
                "job is done.",
            pauses = false,
        ),
        Option(
            type = RISK,
            value = "risk",
            description = "You noticed something that could go wrong, or a consequence of the work " +
                "that the person should weigh. The run carries on.",
            pauses = false,
        ),
        Option(
            type = REVIEW_REQUEST,
            value = "review",
            description = "You want the person to look at what you have produced before you build " +
                "on it.",
            pauses = false,
        ),
    )

    /** One offered type: what it is called to the model, what it stores as, and whether it stops. */
    data class Option(
        val type: String,
        val value: String,
        val description: String,
        val pauses: Boolean,
    )

    /** Resolve what the model asked for, accepting either its value or the stored type. */
    fun option(requested: String): Option? {
        val key = requested.trim().lowercase()
        return OFFERED.firstOrNull { it.value == key || it.type.lowercase() == key }
    }

    /** The values the model is offered, in the order the definition lists them. */
    fun values(): List<String> = OFFERED.map { it.value }

    /** Does this type stop the run? An unknown type never pauses anything. */
    fun pauses(type: String): Boolean = OFFERED.firstOrNull { it.type == type }?.pauses == true

    /** The description list a tool definition prints, so the model sees the same rules. */
    fun describeForModel(): String = OFFERED.joinToString("; ") { "${it.value} — ${it.description}" }
}
