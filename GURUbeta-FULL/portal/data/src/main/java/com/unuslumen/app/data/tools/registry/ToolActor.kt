// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * Who is calling a tool.
 *
 * WHY THIS EXISTS. GURU and every spawned agent write into one Cerebrum brain,
 * and until now nothing on the write path said which of them wrote a fact. The
 * provenance label was built as `guru.role= conversation= message=` because the
 * tool lane never populated a single one of those three fields: `persistFacts`
 * called `BrainService.storeFact(fact)` with one argument and every other
 * parameter took its empty default. So Self Improvement Engine, Code Engineer and
 * GURU reasoning in the main chat all produced byte-identical provenance, and a
 * contradiction between two agents was indistinguishable in storage from one
 * agent changing its mind.
 *
 * THE RULE THIS TYPE ENFORCES IS HONESTY, NOT COMPLETENESS. A field that is not
 * known stays empty rather than being filled with a plausible value. An
 * unattributed call is [UNATTRIBUTED], which writes exactly the provenance the
 * app wrote before this type existed, so every existing memory keeps the identity
 * and the label it already had.
 *
 * WHAT IT IS NOT. This is not authentication. A caller that lies about its own
 * identity is believed, because the actor travels as a value through the
 * dispatcher rather than being proved by the platform. It records attribution for
 * a system whose agents are all running in one process on one device, which is
 * the threat model this app actually has. A model that wanted to forge another
 * agent's name would have to be running code the dispatcher did not hand the
 * actor to.
 */
data class ToolActor(
    /** [KIND_GURU], [KIND_AGENT], or empty for a call nobody claimed. */
    val kind: String = "",
    /** The `guru_agents` row id of a spawned agent. Empty for GURU and for anyone else. */
    val agentId: String = "",
    /** The agent's display name, carried so the label is readable without a database read. */
    val agentName: String = "",
    /** The conversation this call happened inside. Empty when the caller has none. */
    val conversationId: String = "",
    /** The message being answered. Empty when the caller has none. */
    val messageId: String = "",
) {

    /** Nobody claimed this call. Writes the same provenance the app wrote before attribution existed. */
    val isAttributed: Boolean get() = kind.isNotBlank()

    /**
     * What goes in the `role` slot of the brain's provenance label.
     *
     * THE LABEL IS FOR A HUMAN READING A MEMORY, so it names the intelligence
     * rather than its uuid. The uuid is carried separately in [agentId] for any
     * caller that needs to resolve it back to a row.
     *
     * An agent with no name falls back to its id, and an agent with neither gives
     * `agent` rather than an empty string, because "some agent" is a true
     * statement and an empty role reads as "nobody".
     */
    fun roleLabel(): String = when {
        kind == KIND_GURU -> KIND_GURU
        kind == KIND_AGENT && agentName.isNotBlank() -> "$KIND_AGENT:$agentName"
        kind == KIND_AGENT && agentId.isNotBlank() -> "$KIND_AGENT:$agentId"
        kind == KIND_AGENT -> KIND_AGENT
        else -> ""
    }

    /**
     * A one-clause attribution for a provenance string the caller controls.
     *
     * `cortexRecord` takes a free provenance label from the model, so the actor
     * is appended rather than replacing what the model said. An unattributed call
     * appends nothing at all, which is what keeps the default path byte-identical.
     */
    fun provenanceSuffix(): String = when {
        !isAttributed -> ""
        kind == KIND_AGENT && agentId.isNotBlank() -> "actor=agent:$agentId"
        else -> "actor=$kind"
    }

    companion object {
        const val KIND_GURU = "guru"
        const val KIND_AGENT = "agent"

        /** GURU itself, reasoning in the main chat. */
        fun guru(conversationId: String = "", messageId: String = ""): ToolActor =
            ToolActor(
                kind = KIND_GURU,
                conversationId = conversationId,
                messageId = messageId,
            )

        /** A spawned agent, running a job the assistant handed it. */
        fun agent(
            agentId: String,
            agentName: String,
            conversationId: String = "",
            messageId: String = "",
        ): ToolActor = ToolActor(
            kind = KIND_AGENT,
            agentId = agentId,
            agentName = agentName,
            conversationId = conversationId,
            messageId = messageId,
        )

        /**
         * A call nobody claimed.
         *
         * This is the default at every dispatch site so a caller that has not been
         * taught to attribute cannot silently claim to be GURU. It writes the empty
         * provenance the app wrote before this type existed.
         */
        val UNATTRIBUTED = ToolActor()
    }
}
