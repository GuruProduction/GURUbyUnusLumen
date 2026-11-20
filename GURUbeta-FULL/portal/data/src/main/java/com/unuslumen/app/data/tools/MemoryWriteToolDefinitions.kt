// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentMemoryService
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory
import kotlin.reflect.KClass

/**
 * The verb an agent uses to keep something it learned.
 *
 * WHY THIS IS THE MISSING HALF OF AGENT MEMORY. `AgentMemoryService` reads the real brain under the
 * agent's scope, and `spawn` puts what the agent already knows into its prompt. Nothing let an agent
 * write during a run, so every job started from what previous jobs had left and never left anything
 * of its own. That is the difference between a worker and a stateless call.
 *
 * THE SCOPE IS THE GATE, NOT THE TOOL TEXT. `remember` refuses when `writeOwn` is false, and this
 * executor reports that refusal as a sentence rather than as a silent success, because an agent told
 * "saved" when nothing was saved will not try again and will believe the fact is kept.
 *
 * IT WRITES INTO THE AGENT'S OWN DOMAIN. A shared-knowledge write would put one agent's mistake where
 * every other agent reads it as settled, so this only ever writes what this agent knows, with this
 * agent's slug in the provenance. The owner can see it and decide what to promote.
 */
object MemoryWriteToolDefinitions : ToolSetRegistration {

    const val REMEMBER_THIS = "rememberThis"

    override val definitions = listOf(
        ToolDefinition(
            name = REMEMBER_THIS,
            description = "Keep something you learned, so your next job starts from it rather than " +
                "from nothing. Use it for a durable fact about this person's setup, a decision that " +
                "was settled, or something that turned out to be wrong and should not be repeated. " +
                "Write it as a fact rather than as a note to yourself: one sentence, standing alone, " +
                "that would still make sense next week. Do not record the job you were given or " +
                "anything that will be stale tomorrow. Only works if your owner granted you memory " +
                "writes.",
            category = "memory",
            parameters = listOf(
                ToolParameter(
                    name = "fact",
                    type = ToolParameterType.String,
                    required = true,
                    description = "One sentence, standing alone, that a future job could act on " +
                        "without seeing this conversation.",
                ),
                ToolParameter(
                    name = "topic",
                    type = ToolParameterType.String,
                    required = false,
                    description = "A short grouping, such as a device name or an area of work, so " +
                        "related facts sit together. Defaults to general.",
                ),
            ),
            permissions = emptyList(),
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = MemoryWriteToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/**
 * Writes a fact into the calling agent's own memory, and says honestly whether it landed.
 *
 * THE AGENT IS RESOLVED FROM THE RUN, NOT FROM THE ARGUMENTS. A tool argument naming the agent would
 * let it write into another agent's memory, which is the same boundary the decision and report verbs
 * are careful about. The runtime does not pass a run id here; instead the executor is given the agent
 * through the dispatch scope, and a call with no bound agent is refused rather than guessed.
 */
@Factory
class MemoryWriteToolExecutor(
    private val agents: GuruAgentRepository,
    private val memory: AgentMemoryService,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        if (toolName != MemoryWriteToolDefinitions.REMEMBER_THIS) {
            return ToolExecutionResult.error("Unknown tool: $toolName")
        }

        val fact = (args["fact"] as? String)?.trim().orEmpty()
        if (fact.isEmpty()) {
            return ToolExecutionResult.error(
                "Missing 'fact'. Say what should be remembered in one sentence that stands alone."
            )
        }

        /*
         * THE AGENT COMES FROM THE DISPATCH SCOPE. A run knows which agent it belongs to; the model
         * does not get to name one, because a named agent would be a way to write into somebody
         * else's memory with their provenance on it.
         */
        val agentName = com.unuslumen.app.data.agents.currentDispatchScope()?.agentName()
            ?: return success(
                RememberedFact(
                    remembered = false,
                    reason = "Nothing was saved: this call is not attached to a running agent, so " +
                        "there is no memory of yours to write into.",
                ),
                RememberedFact.serializer(),
            )

        val agent = agents.resolveAgent(agentName)
            ?: return success(
                RememberedFact(
                    remembered = false,
                    reason = "Nothing was saved: no agent called '$agentName' exists on this phone.",
                ),
                RememberedFact.serializer(),
            )

        val saved = memory.remember(
            agent = agent,
            fact = fact,
            topic = (args["topic"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "general",
        )

        return success(
            RememberedFact(
                remembered = saved,
                reason = if (saved) {
                    "Saved to your own memory. Your next job will be given it before it starts."
                } else {
                    "Not saved: your owner has not given you permission to write memory. You can " +
                        "still work; you will simply not carry this fact into the next job."
                },
            ),
            RememberedFact.serializer(),
        )
    }

    private fun <T : ToolResultData> success(
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ToolExecutionResult = ToolExecutionResult.success(data, json.encodeToString(serializer, data))
}

/** What happened to the write, in words the agent can act on. */
@Serializable
data class RememberedFact(
    val remembered: Boolean,
    val reason: String,
) : ToolResultData
