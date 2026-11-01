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
 */
object AgentToolDefinitions : ToolSetRegistration {

    const val LIST_AGENTS = "listAgents"
    const val SPAWN_AGENT = "spawnAgent"

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
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = AgentToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
