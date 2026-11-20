// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * The run lifecycle, as names the domain can reason about.
 *
 * WHY THIS IS IN THE DOMAIN AND NOT ONLY ON THE ENTITY. The layering runs database → domain, so a
 * domain type cannot name a constant that lives on an entity without depending on the database
 * module, which it does not and must not. But the room's own state type needs to know which statuses
 * mean "working" and which mean "finished", because that is a product question rather than a storage
 * one.
 *
 * SO THE VOCABULARY LIVES HERE AND THE ENTITY ALIASES IT. There is still exactly one spelling of each
 * name; adding a status means adding it here and to the entity's constant, and the coverage test that
 * already walks the event vocabulary is the pattern for keeping the two in step.
 *
 * THE SETS ARE THE PRODUCT DECISIONS, AND THEY ARE THE POINT.
 *
 *  - [ACTIVE] means work is genuinely in progress. WAITING is included: a run blocked on an answer
 *    or an approval is not idle, it is stuck, and that is a different thing that needs the person.
 *  - [TERMINAL] means finished for good. Nothing resumes it.
 *  - [WAITING] is the subset that released its lease without dying, which is why a waiting run must
 *    not be mistaken for an interrupted one.
 */
object AgentRunStatuses {

    const val CREATED = "CREATED"
    const val STARTING = "STARTING"
    const val RUNNING = "RUNNING"
    const val WAITING_FOR_GURU = "WAITING_FOR_GURU"
    const val WAITING_FOR_USER = "WAITING_FOR_USER"
    const val WAITING_FOR_TOOL = "WAITING_FOR_TOOL"
    const val PAUSED = "PAUSED"
    const val INTEGRATING = "INTEGRATING"
    const val COMPLETED = "COMPLETED"
    const val LIMIT_REACHED = "LIMIT_REACHED"

    /**
     * Stopped unexpectedly, state recoverable.
     *
     * NOT THE SAME AS FAILED. A run the platform killed did not fail at its job; it stopped being
     * held. Marking it FAILED would tell the person the work was no good when the truth is that it
     * is still there and can carry on.
     */
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    /** Every state a run can still move out of. */
    val ACTIVE: Set<String> = setOf(
        CREATED, STARTING, RUNNING, WAITING_FOR_GURU, WAITING_FOR_USER,
        WAITING_FOR_TOOL, PAUSED, INTEGRATING, INTERRUPTED,
    )

    /** States whose time is over. `finished_at` is set and nothing resumes them. */
    val TERMINAL: Set<String> = setOf(COMPLETED, LIMIT_REACHED, FAILED, CANCELLED)

    /** States waiting on a person or on GURU rather than on a running loop. */
    val WAITING: Set<String> = setOf(WAITING_FOR_GURU, WAITING_FOR_USER)

    /** Every name, so a test can prove nothing fell out of the vocabulary. */
    val ALL: List<String> = listOf(
        CREATED, STARTING, RUNNING, WAITING_FOR_GURU, WAITING_FOR_USER, WAITING_FOR_TOOL,
        PAUSED, INTEGRATING, COMPLETED, LIMIT_REACHED, INTERRUPTED, FAILED, CANCELLED,
    )

    /** Is this a status the vocabulary knows? A typo is a run the room cannot classify. */
    fun isKnown(status: String): Boolean = status in ALL
}

/**
 * Who can speak in a run's conversation.
 *
 * HERE FOR THE SAME REASON AS THE STATUSES, AND WITH MORE AT STAKE. The specification's section 2.5
 * rule is that an agent's message is never presented as though GURU authored it, and that guarantee
 * starts with the names being unambiguous. Four participants, and no surface may infer the author
 * from the message's position.
 */
object AgentMessageAuthors {
    const val USER = "USER"
    const val GURU = "GURU"
    const val AGENT = "AGENT"
    const val SYSTEM = "SYSTEM"

    val ALL: List<String> = listOf(USER, GURU, AGENT, SYSTEM)

    fun isKnown(author: String): Boolean = author in ALL

    /** The label a person reads. Never a guess: an unknown author says so rather than defaulting. */
    fun labelFor(author: String, agentName: String): String = when (author) {
        USER -> "You"
        GURU -> "GURU"
        AGENT -> agentName
        SYSTEM -> "System"
        else -> "Unknown participant ($author)"
    }
}

/**
 * What a message is.
 *
 * THE TYPES THE SPECIFICATION NAMES, as names a surface can reason about. Here rather than only on
 * the entity for the same layering reason as the statuses: a UI module may name a message type
 * without depending on the database module, and a screen that had to depend on Room to say
 * "instruction" would be a screen with the wrong dependency.
 */
object AgentMessageTypes {
    const val ASSIGNMENT = "ASSIGNMENT"
    const val ASSIGNMENT_AMENDMENT = "ASSIGNMENT_AMENDMENT"
    const val QUESTION = "QUESTION"
    const val ANSWER = "ANSWER"
    const val CLARIFICATION = "CLARIFICATION"
    const val INSTRUCTION = "INSTRUCTION"
    const val FINDING = "FINDING"
    const val EVIDENCE = "EVIDENCE"
    const val BLOCKER = "BLOCKER"
    const val PROGRESS_REQUEST = "PROGRESS_REQUEST"
    const val COMPLETION = "COMPLETION"
    const val ERROR = "ERROR"
    const val CANCELLATION = "CANCELLATION"
    const val APPROVAL_REQUEST = "APPROVAL_REQUEST"
    const val APPROVAL_DECISION = "APPROVAL_DECISION"

    val ALL: List<String> = listOf(
        ASSIGNMENT, ASSIGNMENT_AMENDMENT, QUESTION, ANSWER, CLARIFICATION, INSTRUCTION,
        FINDING, EVIDENCE, BLOCKER, PROGRESS_REQUEST, COMPLETION, ERROR, CANCELLATION,
        APPROVAL_REQUEST, APPROVAL_DECISION,
    )
}

/**
 * How far a message has got.
 *
 * FOUR STATES, AND THE DIFFERENCE BETWEEN THE MIDDLE TWO IS THE HONEST ONE. A message can be
 * delivered to the runtime and never read by the model, so "delivered" must never imply "influenced
 * the work".
 */
object AgentDeliveryStates {
    const val QUEUED = "QUEUED"
    const val DELIVERED = "DELIVERED"
    const val PROCESSED = "PROCESSED"
    const val FAILED = "FAILED"

    val ALL: List<String> = listOf(QUEUED, DELIVERED, PROCESSED, FAILED)
}
