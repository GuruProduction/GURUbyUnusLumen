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
 * The agent verbs — the numen's own list and call surface.
 *
 * `listAgents` exists so the numen can see what it has before guessing a name.
 * `spawnAgent` is the verb that actually does the work: hand a named agent a
 * job, wait for its report, carry on. Creating and editing agents is the
 * person's job in the creator screen, not a tool, so there is no saveAgent
 * here on purpose.
 *
 * THE CATALOGUE TOOLS REACH FURTHER THAN THIS PHONE. The three below were added
 * because GURU could not answer "is there an agent for this?" at all: `listAgents`
 * reads the local table, so an agent that exists in the catalogue but is not
 * installed was invisible to it. The person had a marketplace screen; the numen had
 * nothing. So it could not suggest one, could not fetch it, and could not add it.
 *
 * BROWSE, FETCH AND INSTALL ARE DELIBERATELY SEPARATE. They fail differently and a
 * caller needs to tell them apart: browsing can be offline, a fetch can name a slug
 * that does not exist, and an install can only happen once both have succeeded. One
 * merged tool would report all three as one failure.
 */
object AgentToolDefinitions : ToolSetRegistration {

    const val LIST_AGENTS = "listAgents"
    const val SPAWN_AGENT = "spawnAgent"

    /** Answer a question a running agent is waiting on, and let it carry on. */
    const val RESUME_AGENT = "resumeAgent"

    /** The pause an agent uses to ask the assistant something mid-run. */
    const val ASK_ASSISTANT = "askAssistant"

    /** Browse the publisher's catalogue, including agents not installed here. */
    const val BROWSE_AGENT_CATALOGUE = "browseAgentCatalogue"

    /** Read one catalogue agent in full, without installing it. */
    const val FETCH_CATALOGUE_AGENT = "fetchCatalogueAgent"

    /** Add a catalogue agent to this phone. */
    const val INSTALL_CATALOGUE_AGENT = "installCatalogueAgent"

    override val definitions = listOf(
        ToolDefinition(
            name = LIST_AGENTS,
            description = "List the agents available to call. Each entry carries its name, what it is for, and the tools it is allowed to use. Call this before spawning one so the name is exact.",
            category = "agents",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SPAWN_AGENT,
            description = "Start a job with a saved agent. THIS RETURNS IMMEDIATELY: it gives you the run id and STARTED, and the agent carries on in the background. It does NOT wait for the job to finish and it does NOT return the report. The agent runs with its own instructions and only the tools it declares. " +
                "THE OUTCOME ARRIVES LATER, and there are three ways to learn it. listAgentRuns shows the state of everything running; getAgentRun with the run id returns what it did and what it produced; and if the agent needs a decision it becomes WAITING_FOR_GURU and the person is told. " +
                "NEVER ASSUME A SPAWNED JOB FINISHED BECAUSE THIS CALL RETURNED SUCCESS. Returning STARTED means it was accepted, nothing more. If someone asks what an agent produced, read the run rather than reporting what you asked for. " +
                "If the agent stops to ask you something, answer it with resumeAgent using the run id. If it needs stopping, cancelAgentRun. A job can take as many exchanges as it needs and none of them happen inside this call.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "agent",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The agent's slug or exact name, as it appears in listAgents"
                ),
                ToolParameter(
                    name = "job",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The complete instruction. State the goal, the constraints, and what a finished result looks like. The agent has no prior context, so anything it cannot look up has to be in here."
                )
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = RESUME_AGENT,
            description = "Answer the question a waiting agent asked, and let it continue. Pass the sessionId that spawnAgent or the last resumeAgent returned, and the answer to the question it put to you. It returns the same three outcomes as spawnAgent: FINISHED with the report, ANSWERED with a further question, or FAILED. Keep going until it finishes, because an agent left waiting is work half done.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "sessionId",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The sessionId from the spawnAgent or resumeAgent result whose status was ANSWERED."
                ),
                ToolParameter(
                    name = "answer",
                    type = ToolParameterType.String,
                    required = true,
                    description = "Your reply to the question it asked. Be complete: it will not ask the same thing twice, so an unclear answer wastes the exchange."
                )
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = ASK_ASSISTANT,
            description = "Ask the assistant a question when you cannot finish the job without knowing something. You are a spawned agent: this pauses you, returns your question to the assistant that called you, and you resume when it answers. Use it rather than guessing, and rather than stopping with an incomplete report. Ask one specific thing, say why you need it, and say what you will do once you have the answer. Do not use it to ask permission for work you were already told to do.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "question",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The question, as one clear sentence a person could answer without seeing your reasoning."
                ),
                ToolParameter(
                    name = "why",
                    type = ToolParameterType.String,
                    required = false,
                    description = "Why you cannot proceed without it. This is what tells the assistant whether the question is worth answering or whether you should have decided yourself."
                ),
                ToolParameter(
                    name = "options",
                    type = ToolParameterType.String,
                    required = false,
                    description = "Optional: the answers you can act on, separated by a vertical bar, when the question is a choice between known routes."
                )
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = BROWSE_AGENT_CATALOGUE,
            description = "Browse the publisher's agent catalogue, which is every agent that exists to download — including ones not on this phone. Use this when someone asks whether there is an agent for a job, or what agents are available. Each entry carries its slug, name, what it is for, the tools it runs with, the author and whether this phone already has it. This reads the catalogue over the network, so it can answer offline; an offline answer is reported as offline rather than as an empty catalogue.",
            category = "agents",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = FETCH_CATALOGUE_AGENT,
            description = "Read one agent from the catalogue in full by its slug, without adding it to this phone. Use it to show someone exactly what an agent would do — its instructions and the tools it would run with — before they decide to install it. A slug that does not exist and a network that cannot be reached are reported as different failures.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "slug",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The agent's catalogue slug, as browseAgentCatalogue reported it."
                )
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = INSTALL_CATALOGUE_AGENT,
            description = "Add a catalogue agent to this phone so it can be spawned. It arrives with the exact tools and instructions the publisher shipped, reviewed before it went live; nothing about it is rewritten here. An agent already installed under the same slug is replaced by the newer version rather than duplicated. This changes what is on the device, so use it when someone has asked for that agent, not speculatively.",
            category = "agents",
            parameters = listOf(
                ToolParameter(
                    name = "slug",
                    type = ToolParameterType.String,
                    required = true,
                    description = "The agent's catalogue slug, as browseAgentCatalogue reported it."
                )
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = AgentToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
