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
            description = "Hand a job to a saved agent and get its report back. The agent runs with its own instructions and only the tools it declares, does not see this conversation, and cannot reply in chat. Give it everything it needs in the job text: it starts cold. Use this to delegate work that is self-contained, especially anything long enough that doing it inline would stall the conversation.",
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
                    description = "The complete instruction. State the goal, the constraints, and what a finished result looks like. The agent has no prior context."
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
