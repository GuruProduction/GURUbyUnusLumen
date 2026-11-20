// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * Lets a person hold an imported agent to less than the publisher granted it.
 *
 * THE GAP THIS CLOSES. A catalogue agent arrives with exactly the tools it shipped with, which is
 * correct: what a buyer downloaded is what was reviewed, and silently weakening an agent they paid
 * for would be a different product than the one the catalogue described. But "no silent weakening"
 * and "no weakening at all" are different rules, and the specification's section 13 asks for
 * isolation of externally sourced agents. A person who wants an imported agent for one job and does
 * not want it to touch their messages should be able to say so without editing what the publisher
 * wrote.
 *
 * A LOCAL NARROWING IS A REDUCTION, NEVER AN EXTENSION. It can only take away from the published
 * grant. That direction is the whole guarantee: an imported agent can end up with less authority than
 * it shipped with, and never with more, so the catalogue's review remains the ceiling and this is a
 * ceiling below it.
 *
 * IT IS STORED APART FROM THE DEFINITION. The published tool list stays exactly as delivered, so a
 * later version can still be diffed against what the publisher shipped rather than against what this
 * person chose to allow. Rewriting the stored grant would destroy the comparison that
 * [AgentUpdateReview] depends on.
 */
object AgentToolNarrowing {

    /**
     * The tools an imported agent may actually use.
     *
     * AN EMPTY NARROWING MEANS NO NARROWING AT ALL, not "no tools". The distinction matters: the
     * default state of every installed agent is an empty set, and reading that as "this agent may use
     * nothing" would break every catalogue agent on the phone. To hold an agent to no tools, a person
     * disables it rather than narrowing it to nothing, because an agent that exists and can do
     * nothing is a state with no purpose.
     */
    fun effectiveTools(publishedTools: List<String>, narrowedTo: Set<String>): List<String> {
        if (narrowedTo.isEmpty()) return publishedTools
        return publishedTools.filter { it in narrowedTo }
    }

    /** One narrowing decision, and its reasons when it was refused. */
    data class Verdict(
        val permitted: Boolean,
        /** The narrowing that would be applied, when permitted. */
        val tools: Set<String>,
        /** Why it was refused, or a note about what it does. Null when there is nothing to say. */
        val explanation: String?,
    )

    /**
     * Whether a requested narrowing is allowed.
     *
     * THE ONLY REFUSAL IS AN EXTENSION. A person naming a tool the publisher never granted is asking
     * for something the review never saw, and the honest answer is that this cannot add it. Every
     * other shape is permitted, including an identical set, because a no-op narrowing is not an error
     * and refusing it would make a form's Save button fail for no reason.
     */
    fun evaluate(publishedTools: List<String>, requested: Set<String>): Verdict {
        val outside = requested - publishedTools.toSet()
        if (outside.isNotEmpty()) {
            return Verdict(
                permitted = false,
                tools = emptySet(),
                explanation = "This agent was not published with " +
                    outside.sorted().joinToString(", ") +
                    ", so it cannot be allowed here. A local restriction can only take tools away.",
            )
        }

        if (requested.isEmpty()) {
            return Verdict(
                permitted = true,
                tools = emptySet(),
                explanation = "No restriction, so this agent may use everything it was published with.",
            )
        }

        val removed = publishedTools.toSet() - requested
        return Verdict(
            permitted = true,
            tools = requested,
            explanation = if (removed.isEmpty()) {
                "This matches what the agent was published with, so it changes nothing."
            } else {
                "This agent will be held to ${requested.size} of the ${publishedTools.size} tools it " +
                    "was published with."
            },
        )
    }

    /**
     * Tell a person when an update would widen what their local restriction allows.
     *
     * THE ONE CASE WHERE AN UPDATE AND A NARROWING INTERACT. A publisher adding a tool is already
     * gated by [AgentUpdateReview]. If the person has also narrowed the agent, the added tool is
     * outside their restriction, so it does not take effect however the update decision goes. Saying
     * so avoids the appearance of an update that was approved and then did nothing.
     */
    fun noteForUpdate(
        addedTools: Set<String>,
        narrowedTo: Set<String>,
    ): String? {
        if (addedTools.isEmpty() || narrowedTo.isEmpty()) return null
        val blocked = addedTools - narrowedTo
        if (blocked.isEmpty()) return null
        return "The update adds " + blocked.sorted().joinToString(", ") +
            ", which is outside the restriction you set here, so this agent will not gain it."
    }
}
