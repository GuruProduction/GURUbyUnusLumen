// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.AgentSlug
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * The agent authoring verbs, the capability search, and the registry-wide tool list.
 *
 * EVERY REFUSAL IS A SENTENCE A CALLER CAN ACT ON. "Could not save the agent" is the
 * defect this module keeps removing, because it collapses a taken name, a catalogue
 * agent that may not be rewritten, and a broken store into one indistinguishable
 * answer. Each of those returns its own reason here.
 *
 * THE AGENT VERBS ARE REFUSED AS GRANTS. An agent that could spawn or create another
 * agent is a recursion nobody asked for, and one that could grant itself tools has
 * escaped its own permission set. The check is here, in the executor, because the
 * dispatcher cannot know that a tool name arriving as an argument is a permission
 * boundary rather than a string.
 */
class AgentAuthoringToolExecutor(
    private val agentRepository: GuruAgentRepository,
    private val agentCatalogue: AgentCatalogue,
    private val toolRegistry: ToolRegistry,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The verbs no agent may ever be granted.
     *
     * Both agent sets are named, including the read-only ones, because an agent that
     * can list or spawn its siblings is already outside the one job it was given.
     */
    private val reservedVerbs: Set<String> = setOf(
        AgentToolDefinitions.LIST_AGENTS,
        AgentToolDefinitions.SPAWN_AGENT,
        AgentToolDefinitions.BROWSE_AGENT_CATALOGUE,
        AgentToolDefinitions.FETCH_CATALOGUE_AGENT,
        AgentToolDefinitions.INSTALL_CATALOGUE_AGENT,
        AgentAuthoringToolDefinitions.CREATE_AGENT,
        AgentAuthoringToolDefinitions.EDIT_AGENT,
        AgentAuthoringToolDefinitions.DELETE_AGENT,
        AgentAuthoringToolDefinitions.SET_AGENT_ENABLED,
        AgentAuthoringToolDefinitions.PUBLISH_AGENT,
        AgentAuthoringToolDefinitions.SEARCH_AGENTS,
    )

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            AgentAuthoringToolDefinitions.CREATE_AGENT -> createAgent(args)
            AgentAuthoringToolDefinitions.EDIT_AGENT -> editAgent(args)
            AgentAuthoringToolDefinitions.DELETE_AGENT -> deleteAgent(args)
            AgentAuthoringToolDefinitions.SET_AGENT_ENABLED -> setEnabled(args)
            AgentAuthoringToolDefinitions.PUBLISH_AGENT -> publishAgent(args)
            AgentAuthoringToolDefinitions.SEARCH_AGENTS -> searchAgents(args)
            AgentAuthoringToolDefinitions.LIST_TOOLS -> listTools(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    // ------------------------------------------------------------------
    // Authoring
    // ------------------------------------------------------------------

    private suspend fun createAgent(args: Map<String, Any?>): ToolExecutionResult {
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return ToolExecutionResult.error("Missing 'name' parameter")

        val instructions = (args["instructions"] as? String)?.trim().orEmpty()
        if (instructions.isEmpty()) {
            return ToolExecutionResult.error(
                "'instructions' was empty. The instructions are the whole of what an agent is: " +
                    "the job, the standard, and what to do when something is unclear."
            )
        }

        if (!agentRepository.isNameAvailable(name)) {
            return success(
                SaveAgentToolResult(
                    success = false,
                    error = "There is already an agent called '$name' on this phone. Either edit that one, " +
                        "or choose a name that is not in use.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }

        val refused = refusedVerbs(args["tools"])
        if (refused.isNotEmpty()) {
            return success(
                SaveAgentToolResult(
                    success = false,
                    error = "These tools cannot be granted to an agent: ${refused.joinToString(", ")}. " +
                        "An agent may never list, spawn, create, edit, publish or switch on another agent, " +
                        "because that is how one escapes the job it was given.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }

        val unknown = unknownToolNames(args["tools"])
        if (unknown.isNotEmpty()) {
            return success(
                SaveAgentToolResult(
                    success = false,
                    error = "No tool on this phone is called ${unknown.joinToString(", ")}. " +
                        "Run listTools to see the names that exist before granting one.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }

        return try {
            val created = agentRepository.createAgent(
                SaveAgentRequest(
                    name = name,
                    description = (args["description"] as? String)?.trim().orEmpty(),
                    systemPrompt = instructions,
                    toolPermissions = toolNames(args["tools"]).sorted(),
                )
            )
            success(
                SaveAgentToolResult(
                    success = true,
                    created = true,
                    agent = created.toDetail(),
                ),
                SaveAgentToolResult.serializer(),
            )
        } catch (e: Exception) {
            success(
                SaveAgentToolResult(
                    success = false,
                    error = "$name could not be saved: ${e.message ?: e.javaClass.simpleName}. " +
                        "Nothing changed on this phone.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }
    }

    private suspend fun editAgent(args: Map<String, Any?>): ToolExecutionResult {
        val key = (args["agent"] as? String)?.trim().orEmpty()
        if (key.isEmpty()) return ToolExecutionResult.error("Missing 'agent' parameter")

        val existing = agentRepository.resolveAgent(key)
            ?: return success(
                SaveAgentToolResult(
                    success = false,
                    error = "No agent called '$key' on this phone. Run listAgents for the exact names.",
                ),
                SaveAgentToolResult.serializer(),
            )

        if (existing.source != GuruAgent.SOURCE_USER) {
            return success(
                SaveAgentToolResult(
                    success = false,
                    error = "${existing.name} came from the catalogue, so it cannot be edited here. " +
                        "What a buyer downloads is what was reviewed; to change how it behaves, " +
                        "make your own copy with createAgent.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }

        val refused = refusedVerbs(args["tools"])
        if (refused.isNotEmpty()) {
            return success(
                SaveAgentToolResult(
                    success = false,
                    error = "These tools cannot be granted to an agent: ${refused.joinToString(", ")}.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }

        val unknown = unknownToolNames(args["tools"])
        if (unknown.isNotEmpty()) {
            return success(
                SaveAgentToolResult(
                    success = false,
                    error = "No tool on this phone is called ${unknown.joinToString(", ")}. " +
                        "Run listTools to see the names that exist.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }

        // Anything omitted falls back to the stored value, so a caller changing one
        // line does not resend the whole agent and cannot blank a field by accident.
        val request = SaveAgentRequest(
            name = (args["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name,
            description = (args["description"] as? String)?.trim() ?: existing.description,
            systemPrompt = (args["instructions"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing.systemPrompt,
            toolPermissions = if (args.containsKey("tools")) {
                toolNames(args["tools"]).sorted()
            } else {
                existing.toolPermissions
            },
        )

        return try {
            val updated = agentRepository.updateAgent(existing.id, request)
            success(
                SaveAgentToolResult(
                    success = true,
                    created = false,
                    agent = updated.toDetail(),
                ),
                SaveAgentToolResult.serializer(),
            )
        } catch (e: Exception) {
            success(
                SaveAgentToolResult(
                    success = false,
                    error = "${existing.name} could not be changed: ${e.message ?: e.javaClass.simpleName}.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }
    }

    private suspend fun deleteAgent(args: Map<String, Any?>): ToolExecutionResult {
        val key = (args["agent"] as? String)?.trim().orEmpty()
        if (key.isEmpty()) return ToolExecutionResult.error("Missing 'agent' parameter")

        val existing = agentRepository.resolveAgent(key)
            ?: return success(
                AgentStateResult(
                    success = false,
                    error = "No agent called '$key' on this phone, so nothing was deleted.",
                ),
                AgentStateResult.serializer(),
            )

        return try {
            agentRepository.deleteAgent(existing.id)
            success(
                AgentStateResult(
                    success = true,
                    name = existing.name,
                    deleted = true,
                ),
                AgentStateResult.serializer(),
            )
        } catch (e: Exception) {
            // The repository refuses a catalogue agent with a sentence written for a
            // person; it is passed straight through rather than wrapped.
            success(
                AgentStateResult(
                    success = false,
                    name = existing.name,
                    error = e.message ?: "${existing.name} could not be deleted.",
                ),
                AgentStateResult.serializer(),
            )
        }
    }

    private suspend fun setEnabled(args: Map<String, Any?>): ToolExecutionResult {
        val key = (args["agent"] as? String)?.trim().orEmpty()
        if (key.isEmpty()) return ToolExecutionResult.error("Missing 'agent' parameter")
        val enabled = args["enabled"] as? Boolean
            ?: return ToolExecutionResult.error("Missing 'enabled' parameter, true or false")

        val existing = agentRepository.resolveAgent(key)
            ?: return success(
                AgentStateResult(
                    success = false,
                    error = "No agent called '$key' on this phone.",
                ),
                AgentStateResult.serializer(),
            )

        return try {
            val stored = if (enabled) {
                agentRepository.enableAgent(existing.id)
            } else {
                agentRepository.disableAgent(existing.id)
            }
            success(
                AgentStateResult(
                    success = true,
                    name = stored.name,
                    // The stored state, not the requested one: a store that refused
                    // would otherwise be reported as having obeyed.
                    enabled = stored.enabled,
                ),
                AgentStateResult.serializer(),
            )
        } catch (e: Exception) {
            success(
                AgentStateResult(
                    success = false,
                    name = existing.name,
                    enabled = existing.enabled,
                    error = "${existing.name} could not be changed: ${e.message ?: e.javaClass.simpleName}.",
                ),
                AgentStateResult.serializer(),
            )
        }
    }

    private suspend fun publishAgent(args: Map<String, Any?>): ToolExecutionResult {
        val key = (args["agent"] as? String)?.trim().orEmpty()
        if (key.isEmpty()) return ToolExecutionResult.error("Missing 'agent' parameter")

        val existing = agentRepository.resolveAgent(key)
            ?: return success(
                SaveAgentToolResult(
                    success = false,
                    error = "No agent called '$key' on this phone.",
                ),
                SaveAgentToolResult.serializer(),
            )

        if (existing.source != GuruAgent.SOURCE_USER) {
            return success(
                SaveAgentToolResult(
                    success = false,
                    error = "Only an agent you made yourself can be published. ${existing.name} came from " +
                        "the catalogue, so it already belongs to whoever published it.",
                ),
                SaveAgentToolResult.serializer(),
            )
        }

        val priceRaw = (args["price"] as? String)?.trim().orEmpty()
        val pence = penceFromPounds(priceRaw)
            ?: return success(
                SaveAgentToolResult(
                    success = false,
                    error = "'$priceRaw' is not a price. Write 0 for free, or an amount like 2.50.",
                ),
                SaveAgentToolResult.serializer(),
            )

        val category = (args["category"] as? String)?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() } ?: "other"

        val outcome = agentCatalogue.submitForReview(
            // The agent's own slug, not a fresh one: a rename must not silently
            // publish a second copy under a new identity.
            slug = existing.slug.ifBlank { AgentSlug.from(existing.name) },
            name = existing.name,
            roleDescription = existing.description,
            systemPrompt = existing.systemPrompt,
            toolPermissions = existing.toolPermissions,
            authorLabel = null,
            priceCents = pence,
            category = category,
        )

        return success(
            SaveAgentToolResult(
                success = outcome.success,
                created = false,
                agent = existing.toDetail(),
                error = if (outcome.success) null else outcome.message,
            ),
            SaveAgentToolResult.serializer(),
        )
    }

    // ------------------------------------------------------------------
    // Choosing between agents
    // ------------------------------------------------------------------

    /**
     * Agents that could do a job, scored by how they match it.
     *
     * WHY THE SCORE AND THE REASON. A caller choosing between three agents needs to
     * know whether the name matched, the description matched, or the agent already
     * holds the tool the job needs. Those are different strengths of evidence, and a
     * bare list of names throws all of it away.
     *
     * THE TOOL MATCH IS THE STRONGEST SIGNAL, because it is the one that says the agent
     * can actually do the thing rather than talk about it. A description match is next;
     * a name match is weakest, because a name is a label somebody chose.
     */
    private suspend fun searchAgents(args: Map<String, Any?>): ToolExecutionResult {
        val query = (args["query"] as? String)?.trim().orEmpty()
        val enabledOnly = args["enabledOnly"] as? Boolean ?: false

        val all = agentRepository.getAllAgents()
            .filter { !enabledOnly || it.enabled }

        if (query.isEmpty()) {
            val matches = all.map { agent ->
                AgentMatch(
                    slug = agent.slug,
                    name = agent.name,
                    description = agent.description,
                    tools = agent.toolPermissions,
                    enabled = agent.enabled,
                    score = 0,
                    why = "no query, so every agent is listed",
                )
            }
            return success(
                SearchAgentsResult(success = true, total = matches.size, matches = matches),
                SearchAgentsResult.serializer(),
            )
        }

        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        if (terms.isEmpty()) {
            return success(
                SearchAgentsResult(
                    success = false,
                    error = "'$query' has no word long enough to search on. Try a word of three letters or more.",
                ),
                SearchAgentsResult.serializer(),
            )
        }

        val scored = all.mapNotNull { agent ->
            var score = 0
            val reasons = mutableListOf<String>()

            val name = agent.name.lowercase()
            val description = agent.description.lowercase()
            val instructions = agent.systemPrompt.lowercase()
            val tools = agent.toolPermissions.map { it.lowercase() }

            terms.forEach { term ->
                if (tools.any { it.contains(term) }) {
                    score += 4
                    reasons.add("holds a tool matching '$term'")
                }
                if (description.contains(term)) {
                    score += 3
                    reasons.add("describes itself with '$term'")
                }
                if (instructions.contains(term)) {
                    score += 2
                    reasons.add("its instructions mention '$term'")
                }
                if (name.contains(term)) {
                    score += 1
                    reasons.add("its name contains '$term'")
                }
            }

            if (score == 0) return@mapNotNull null
            AgentMatch(
                slug = agent.slug,
                name = agent.name,
                description = agent.description,
                tools = agent.toolPermissions,
                enabled = agent.enabled,
                score = score,
                why = reasons.distinct().joinToString("; "),
            )
        }.sortedByDescending { it.score }

        return success(
            SearchAgentsResult(
                success = true,
                total = scored.size,
                matches = scored,
                error = if (scored.isEmpty()) {
                    "No agent on this phone matches '$query'. Run listAgents to see everything that exists, " +
                        "or createAgent to make one for this job."
                } else null,
            ),
            SearchAgentsResult.serializer(),
        )
    }

    // ------------------------------------------------------------------
    // The registry
    // ------------------------------------------------------------------

    /**
     * The tools this phone actually holds.
     *
     * `getMyTools` answers "what have I made", because it reads the model's own
     * definitions. This answers "what do I have", which is what a permission audit, a
     * grant and a routing decision all need first.
     */
    private fun listTools(args: Map<String, Any?>): ToolExecutionResult {
        val category = (args["category"] as? String)?.trim()?.lowercase()
        val query = (args["query"] as? String)?.trim()?.lowercase()
        val permission = (args["permission"] as? String)?.trim()?.lowercase()

        val filtered = !category.isNullOrEmpty() || !query.isNullOrEmpty() || !permission.isNullOrEmpty()

        val entries = toolRegistry.getAllDefinitions()
            .asSequence()
            .filter { definition ->
                category.isNullOrEmpty() || definition.category.lowercase() == category
            }
            .filter { definition ->
                permission.isNullOrEmpty() ||
                    definition.permissions.any { it.lowercase().contains(permission) }
            }
            .filter { definition ->
                query.isNullOrEmpty() ||
                    definition.name.lowercase().contains(query) ||
                    definition.description.lowercase().contains(query)
            }
            .map { definition ->
                ToolCatalogueEntry(
                    name = definition.name,
                    category = definition.category,
                    description = definition.description,
                    permissions = definition.permissions,
                    parameters = definition.parameters.map { it.name },
                )
            }
            .sortedWith(compareBy({ it.category }, { it.name }))
            .toList()

        return success(
            ListToolsResult(
                success = true,
                total = entries.size,
                filtered = filtered,
                tools = entries,
            ),
            ListToolsResult.serializer(),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Tool names from a JSON array string, tolerant of a single bare name. */
    private fun toolNames(raw: Any?): List<String> {
        val text = when (raw) {
            null -> return emptyList()
            is String -> raw.trim()
            is JsonArray -> raw.toString()
            else -> raw.toString().trim()
        }
        if (text.isEmpty()) return emptyList()

        if (text.startsWith("[")) {
            return runCatching {
                json.parseToJsonElement(text).jsonArray
                    .mapNotNull { element ->
                        // A name that is not a string is a shape a caller got wrong;
                        // it is dropped rather than stringified, so "null" can never
                        // become a grant.
                        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
        }

        // A bare name, or a comma separated list, is a shape a model actually sends.
        return text.split(",").map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
    }

    private fun refusedVerbs(raw: Any?): List<String> =
        toolNames(raw).filter { it in reservedVerbs }

    /**
     * Names that no tool on this phone answers to.
     *
     * THE REGISTRY IS THE AUTHORITY, not the picker. A name that does not resolve would
     * otherwise be stored as a grant that never fires, which reads to a caller as an
     * agent that was given a capability and is inexplicably not using it.
     */
    private fun unknownToolNames(raw: Any?): List<String> =
        toolNames(raw).filter { !toolRegistry.hasTool(it) }

    /**
     * Pounds to pence, or null when the box does not hold a usable number.
     *
     * One conversion, at the edge, the same rule the creator screen uses: a null is a
     * refusal to publish, never a default, because publishing with an unreadable price
     * either charges a stranger wrongly or gives away work meant to be sold.
     */
    private fun penceFromPounds(raw: String): Long? {
        val trimmed = raw.trim().removePrefix("£").trim()
        if (trimmed.isEmpty()) return 0L
        val pounds = trimmed.toDoubleOrNull() ?: return null
        if (pounds < 0.0) return null
        return Math.round(pounds * 100.0)
    }

    private fun GuruAgent.toDetail(): AgentDetail = AgentDetail(
        id = id,
        slug = slug,
        name = name,
        description = description,
        systemPrompt = systemPrompt,
        tools = toolPermissions,
        toolCount = toolPermissions.size,
        enabled = enabled,
        source = source,
        userOwned = isUserOwned,
        version = version,
        updatedAt = updatedAt,
    )

    private fun <T : ToolResultData> success(
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ToolExecutionResult = ToolExecutionResult.success(data, json.encodeToString(serializer, data))
}
