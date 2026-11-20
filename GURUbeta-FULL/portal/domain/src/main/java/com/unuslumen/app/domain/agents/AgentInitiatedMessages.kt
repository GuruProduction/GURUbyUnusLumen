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

/**
 * What a person may say to a running agent, and what each kind means.
 *
 * SECTION 9.9 NAMES SEVEN: questions, information, corrections, instructions, priority changes,
 * evidence requests and control requests. The room offered two, so a person wanting to ask why could
 * only send what read as an instruction, and the agent treated it as one.
 *
 * THE KINDS MAP ONTO THE BROKER'S OWN TYPES RATHER THAN INVENTING NEW ONES. A question is a
 * QUESTION, an evidence request is an EVIDENCE message, a control request is a PROGRESS_REQUEST,
 * and a correction is an amendment because that is what changing the job is. The stored type is what
 * the runtime and the room read, so this is a vocabulary for the person rather than a second one for
 * the machine.
 *
 * EVERY KIND HERE IS A MESSAGE THE RUNTIME TAKES AT A ROUND BOUNDARY. None of them interrupts a tool
 * call in flight, and none of them is a control that acts on the run directly: pausing and stopping
 * are their own buttons because they do something a message does not.
 */
object AgentSpeakableTo {

    /** Ask why, or what it means. The agent answers in its next turn. */
    const val QUESTION = AgentMessageTypes.QUESTION

    /** Tell it something it did not know. */
    const val INFORMATION = AgentMessageTypes.EVIDENCE

    /** Change what it is doing, or the order it is doing it in. */
    const val CORRECTION = AgentMessageTypes.ASSIGNMENT_AMENDMENT

    /** A plain instruction for the work. */
    const val INSTRUCTION = AgentMessageTypes.INSTRUCTION

    /** Ask to see the evidence behind a claim. */
    const val EVIDENCE_REQUEST = AgentMessageTypes.EVIDENCE

    /** Ask where it has got to. */
    const val CONTROL_REQUEST = AgentMessageTypes.PROGRESS_REQUEST

    data class Option(val type: String, val label: String, val hint: String)

    /**
     * The choices the composer offers.
     *
     * ONLY TYPES THAT CHANGE HOW THE AGENT TREATS THE WORDS. A correction delivered as an instruction
     * would leave the original brief apparently intact and the agent unsure whether it was being
     * redirected; a question delivered as an instruction would read as an order to answer rather than
     * as somebody asking.
     */
    val OPTIONS: List<Option> = listOf(
        Option(INSTRUCTION, "INSTRUCTION", "Tell it what to do next"),
        Option(CORRECTION, "CHANGE THE JOB", "Amend the brief; the original stays as it was"),
        Option(QUESTION, "ASK", "Ask why, or what it means"),
        Option(INFORMATION, "TELL IT", "Give it something it did not know"),
        Option(EVIDENCE_REQUEST, "ASK FOR PROOF", "Ask for the evidence behind a claim"),
        Option(CONTROL_REQUEST, "WHERE IS IT", "Ask where it has got to"),
    )

    private val known: Map<String, Option> = OPTIONS.associateBy { it.type }

    /** Is this a type a person may send from the room? */
    fun allows(type: String): Boolean = known.containsKey(type)

    /** The words the button shows for a stored type, or the type itself when unrecognised. */
    fun labelFor(type: String): String = known[type]?.label ?: type
}
