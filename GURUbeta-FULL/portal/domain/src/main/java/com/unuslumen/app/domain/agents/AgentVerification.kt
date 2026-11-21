// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * How far a claim has actually been confirmed.
 *
 * WHY THREE LEVELS RATHER THAN A BOOLEAN. Section 4.4 asks the system to distinguish an attempt, an
 * acceptance and a real-world confirmation, and those are three different facts:
 *
 *  - ATTEMPTED means the agent did something and has not checked what happened. A tool call that
 *    returned is an attempt, not a result.
 *  - ACCEPTED means the mechanism that received it acknowledged it. An API answering 200, a command
 *    exiting zero, a file being written.
 *  - CONFIRMED means the effect was observed independently of the thing that performed it. The file
 *    was read back, the row was queried, the second source agreed.
 *
 * A boolean would collapse all three into "done", which is the collapse the specification exists to
 * prevent. An agent that reports a send as confirmed because the tool call returned has told the
 * person something it did not check.
 */
object VerificationLevels {
    const val ATTEMPTED = "ATTEMPTED"
    const val ACCEPTED = "ACCEPTED"
    const val CONFIRMED = "CONFIRMED"

    val ALL: List<String> = listOf(ATTEMPTED, ACCEPTED, CONFIRMED)

    /**
     * The level, or null when the caller wrote something else.
     *
     * NULL RATHER THAN THE NEAREST LEVEL. Filing an unrecognised word under "attempted" would make a
     * typo look like modesty and would lose the check that was actually made, so an unknown level is
     * refused and the executor says which ones exist.
     */
    fun of(requested: String): String? {
        val key = requested.trim().uppercase()
        return if (key in ALL) key else null
    }

    /** Can a claim at this level support saying the work is done? */
    fun supportsCompletion(level: String): Boolean = level == CONFIRMED

    /** The words a person reads, so a level is never shown as its raw constant. */
    fun label(level: String): String = when (level) {
        ATTEMPTED -> "attempted, not checked"
        ACCEPTED -> "accepted by the mechanism, not confirmed on the device"
        CONFIRMED -> "confirmed by an independent check"
        else -> level
    }
}

/**
 * One check the agent made, as a fact rather than as a sentence in its report.
 *
 * THE FIELDS ARE THE SPECIFICATION'S. [claim] is what is being asserted. [level] is how far it has
 * been confirmed. [evidence] is what was observed and where, because a confirmation with no source is
 * an assertion with a longer word. [check] is what was done to confirm it, which is the difference
 * between "I checked" and "I did this and here is what came back". [uncertainty] is what remains open.
 *
 * A VERIFICATION IS NOT A DECISION. A decision records a fork in the work; a verification records
 * whether something the work produced is true. Both live in the ledger and neither is the reasoning
 * channel.
 */
object AgentVerification {

    /** The name of the tool, duplicated for the reason the other verbs are, and pinned by a test. */
    const val RECORD_VERIFICATION = "recordVerification"

    /** The event a recorded check becomes. */
    const val EVENT = AgentEventTypes.VERIFICATION_RECORDED
}
