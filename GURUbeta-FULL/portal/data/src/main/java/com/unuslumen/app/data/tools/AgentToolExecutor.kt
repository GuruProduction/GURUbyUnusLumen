// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json

/**
 * The two agent verbs, wired to the store and the runtime.
 *
 * A spawn failure is returned as a successful tool call carrying
 * success=false and a sentence, never as a thrown error, because the numen has
 * to be able to tell "that agent is switched off" from "the tool broke" and act
 * on the first one.
 */
class AgentToolExecutor(
    private val agentRepository: GuruAgentRepository,
    private val agentRuntime: AgentRuntime
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            AgentToolDefinitions.LIST_AGENTS -> listAgents()
            AgentToolDefinitions.SPAWN_AGENT -> spawnAgent(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun listAgents(): ToolExecutionResult {
        val agents = agentRepository.getAllAgents()
        val entries = agents.map { agent ->
            AgentListingEntry(
                slug = agent.slug,
                name = agent.name,
                description = agent.description.ifBlank {
                    agent.systemPrompt.lineSequence().firstOrNull()?.take(140) ?: ""
                },
                tools = agent.toolPermissions,
                enabled = agent.enabled,
                source = agent.source
            )
        }
        val result = ListAgentsResult(
            total = entries.size,
            agents = entries,
            note = if (entries.isEmpty()) {
                "No agents yet. One can be made in the Agents screen of the app."
            } else null
        )
        return ToolExecutionResult.success(
            result,
            json.encodeToString(ListAgentsResult.serializer(), result)
        )
    }

    private suspend fun spawnAgent(args: Map<String, Any?>): ToolExecutionResult {
        val agentName = (args["agent"] as? String)?.trim()
            ?: return ToolExecutionResult.error("Missing 'agent' parameter")
        val job = (args["job"] as? String)?.trim()
            ?: return ToolExecutionResult.error("Missing 'job' parameter")

        if (agentName.isEmpty()) return ToolExecutionResult.error("'agent' was empty")
        if (job.isEmpty()) {
            return ToolExecutionResult.error(
                "'job' was empty. The agent starts cold, so the job text has to carry the whole instruction."
            )
        }

        val outcome = agentRuntime.spawn(agentName, job).last()
        val result = SpawnAgentResult(
            success = outcome.success,
            agent = outcome.agentName,
            report = outcome.report,
            toolRounds = outcome.toolRounds,
            error = outcome.error
        )
        return ToolExecutionResult.success(
            result,
            json.encodeToString(SpawnAgentResult.serializer(), result)
        )
    }
}
