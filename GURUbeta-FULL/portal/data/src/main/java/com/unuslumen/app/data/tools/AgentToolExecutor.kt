// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.repository.AgentCatalogue
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
    private val agentRuntime: AgentRuntime,
    private val agentCatalogue: AgentCatalogue
) : ToolExecutor {

    /**
     * ENCODE DEFAULTS, OR A FIELD CAN VANISH FROM A RESULT.
     *
     * Without this, a value equal to its declared default is omitted from the JSON. That
     * turned `total: 0` and `agents: []` into absent keys, so a caller reading the payload
     * could not tell "the catalogue answered and is empty" from "the tool did not report a
     * count at all". Every other executor in this module already sets it; this one was
     * written without it, which is exactly the kind of silent shape difference that reads
     * as a working tool until somebody parses the result.
     */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            AgentToolDefinitions.LIST_AGENTS -> listAgents()
            AgentToolDefinitions.SPAWN_AGENT -> spawnAgent(args)
            AgentToolDefinitions.BROWSE_AGENT_CATALOGUE -> browseCatalogue()
            AgentToolDefinitions.FETCH_CATALOGUE_AGENT -> fetchCatalogueAgent(args)
            AgentToolDefinitions.INSTALL_CATALOGUE_AGENT -> installCatalogueAgent(args)
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

    // ------------------------------------------------------------------
    // The catalogue: reach past this phone
    // ------------------------------------------------------------------

    /**
     * Browse everything published, including what this phone does not have.
     *
     * OFFLINE IS NOT AN EMPTY CATALOGUE. The transport returning null means GURU could
     * not ask; the publisher returning a list means it did ask and that is the answer.
     * Reporting both as "no agents" would tell a person their catalogue is empty when
     * their Tor circuit is down, which is the same fault as an empty ARP scan being read
     * as an empty network.
     */
    private suspend fun browseCatalogue(): ToolExecutionResult {
        val catalogue = agentCatalogue.listCatalogue()
            ?: return success(
                BrowseCatalogueResult(
                    success = false,
                    reachable = false,
                    error = "The catalogue could not be reached, so this is not an answer about what " +
                        "exists. Tor may be down or the publisher may be unreachable. Anything already " +
                        "installed on this phone still works.",
                ),
                BrowseCatalogueResult.serializer(),
            )

        // Versions come from the manifest, which is the only place they are published.
        // A missing manifest is not fatal: the rows carry publishedVersion null and a
        // caller can still browse and install.
        val published = agentCatalogue.publishedVersions()
        val installedBySlug = agentRepository.getSyncedAgents().associateBy { it.slug }

        val entries = catalogue.map { entry ->
            CatalogueEntryView(
                slug = entry.slug,
                name = entry.name,
                roleDescription = entry.roleDescription,
                tools = entry.toolPermissions,
                authorLabel = entry.authorLabel,
                priceCents = entry.priceCents,
                installed = installedBySlug.containsKey(entry.slug),
                installedVersion = installedBySlug[entry.slug]?.version,
                publishedVersion = published?.get(entry.slug),
            )
        }.sortedBy { it.name.lowercase() }

        return success(
            BrowseCatalogueResult(
                success = true,
                reachable = true,
                total = entries.size,
                agents = entries,
                error = if (entries.isEmpty()) {
                    "The catalogue answered and has nothing published yet."
                } else null,
            ),
            BrowseCatalogueResult.serializer(),
        )
    }

    /** One catalogue agent in full, without installing it. */
    private suspend fun fetchCatalogueAgent(args: Map<String, Any?>): ToolExecutionResult {
        val slug = (args["slug"] as? String)?.trim()
        if (slug.isNullOrEmpty()) return ToolExecutionResult.error("Missing 'slug' parameter")

        val agent = agentCatalogue.fetchAgent(slug)
            ?: return success(
                FetchCatalogueAgentResult(
                    success = false,
                    slug = slug,
                    error = "No agent called '$slug' could be read. Either no agent has that slug, or " +
                        "the catalogue could not be reached. Run browseAgentCatalogue to see the exact " +
                        "slugs that exist.",
                ),
                FetchCatalogueAgentResult.serializer(),
            )

        val installed = agentRepository.getSyncedAgents().any { it.slug == agent.slug }
        return success(
            FetchCatalogueAgentResult(
                success = true,
                slug = agent.slug,
                name = agent.name,
                roleDescription = agent.roleDescription,
                systemPrompt = agent.systemPrompt,
                tools = agent.toolPermissions,
                authorLabel = agent.authorLabel,
                installed = installed,
            ),
            FetchCatalogueAgentResult.serializer(),
        )
    }

    /**
     * Add a catalogue agent to this phone.
     *
     * IT INSTALLS THROUGH THE SYNCED LANE, exactly as the marketplace screen does. That
     * is what makes three things true: the agent can be force-updated later, it can be
     * withdrawn when the publisher retracts it, and it can never overwrite an agent the
     * person wrote themselves.
     *
     * ITS TOOLS ARE NOT FILTERED. An agent arrives with the permissions the publisher
     * shipped and the review approved; removing some here would mean a person who paid
     * for an agent got a weaker one than the one that was reviewed, which is a different
     * product than the one the catalogue described.
     */
    private suspend fun installCatalogueAgent(args: Map<String, Any?>): ToolExecutionResult {
        val slug = (args["slug"] as? String)?.trim()
        if (slug.isNullOrEmpty()) return ToolExecutionResult.error("Missing 'slug' parameter")

        val fetched = agentCatalogue.fetchAgent(slug)
            ?: return success(
                InstallCatalogueAgentResult(
                    success = false,
                    slug = slug,
                    error = "Nothing was installed: '$slug' could not be read from the catalogue. Either " +
                        "no agent has that slug, or the catalogue could not be reached.",
                ),
                InstallCatalogueAgentResult.serializer(),
            )

        // The version the catalogue publishes, so a later manifest pass does not
        // immediately reinstall what was just installed.
        val publishedVersion = agentCatalogue.publishedVersions()?.get(fetched.slug) ?: 1
        val alreadyInstalled = agentRepository.getSyncedAgents().any { it.slug == fetched.slug }

        return try {
            agentRepository.installSyncedAgent(
                slug = fetched.slug,
                name = fetched.name,
                description = fetched.roleDescription,
                systemPrompt = fetched.systemPrompt,
                toolPermissions = fetched.toolPermissions,
                version = publishedVersion,
            )
            success(
                InstallCatalogueAgentResult(
                    success = true,
                    slug = fetched.slug,
                    name = fetched.name,
                    version = publishedVersion,
                    tools = fetched.toolPermissions,
                    replaced = alreadyInstalled,
                ),
                InstallCatalogueAgentResult.serializer(),
            )
        } catch (e: Exception) {
            success(
                InstallCatalogueAgentResult(
                    success = false,
                    slug = fetched.slug,
                    name = fetched.name,
                    error = "${fetched.name} could not be added: " +
                        "${e.message ?: e.javaClass.simpleName}. Nothing changed on this phone.",
                ),
                InstallCatalogueAgentResult.serializer(),
            )
        }
    }

    /** Serialize one result and hand it back as a successful tool call. */
    private fun <T : ToolResultData> success(
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ToolExecutionResult = ToolExecutionResult.success(data, json.encodeToString(serializer, data))
}
