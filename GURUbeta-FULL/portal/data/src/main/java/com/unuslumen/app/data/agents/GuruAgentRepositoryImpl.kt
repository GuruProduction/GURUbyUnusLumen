// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.withTransaction
import com.unuslumen.app.database.dao.AgentDefinitionVersionDao
import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.database.entity.AgentDefinitionVersionEntity
import com.unuslumen.app.database.entity.GuruAgentEntity
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.model.AgentDefinitionSnapshot
import com.unuslumen.app.domain.model.AgentSlug
import com.unuslumen.app.domain.model.AgentSummary
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The agent store, backed by guru_agents.
 *
 * THE TRUST BOUNDARY. A row with source "user" is the person's own agent. It is
 * editable, disableable and deletable by them and by nothing else. The sync
 * worker lands "synced" rows, force-pushes newer versions of them, and withdraws
 * them when the publication disappears, and every one of those paths filters on
 * source = "synced". Nothing in this class lets a catalogue operation reach a
 * user row, which is the same guarantee the skills lane already enforces.
 *
 * ONE LIVE COPY. A republish lands as a fresh row and disables the older synced
 * row of the same name rather than mutating it, so a rollback is always possible
 * by re-enabling the previous row.
 *
 * EVERY SAVE APPENDS A DEFINITION VERSION. Before this, `updateAgent` overwrote
 * the row in place and the previous instructions, tools and configuration ceased
 * to exist. Now the write and the history row happen in one transaction, so an
 * edit either lands with its history or does not land at all. A definition that
 * cannot be recorded is a definition somebody will later be unable to explain.
 */
@OptIn(ExperimentalUuidApi::class)
@Single(binds = [GuruAgentRepository::class])
class GuruAgentRepositoryImpl(
    private val guruAgentDao: GuruAgentDao,
    private val definitionVersionDao: AgentDefinitionVersionDao,
    /**
     * How the live row and its history are made atomic.
     *
     * Production supplies a Room transaction, so the two writes roll back together. A store with
     * no transaction boundary supplies [com.unuslumen.app.domain.repository.AgentStoreTransactor.direct]
     * and thereby states that it cannot roll back, rather than this class assuming either.
     */
    private val transactor: com.unuslumen.app.domain.repository.AgentStoreTransactor,
    private val toolRegistry: com.unuslumen.app.data.tools.registry.ToolRegistry
) : GuruAgentRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The picker's list: every registered tool except the agent verbs, which no
     * agent may ever hold. Sorted by category then name so a long catalogue
     * groups sensibly instead of arriving in registry order.
     */
    override suspend fun grantableTools(): List<com.unuslumen.app.domain.repository.GrantableTool> =
        withContext(Dispatchers.IO) {
            toolRegistry.getAllDefinitions()
                .filter { it.name !in AgentRuntime.NEVER_SPAWNABLE }
                .map {
                    com.unuslumen.app.domain.repository.GrantableTool(
                        name = it.name,
                        category = it.category.ifBlank { "other" },
                        description = it.description
                    )
                }
                .sortedWith(compareBy({ it.category }, { it.name }))
        }

    override fun getAllAgentsFlow(): Flow<List<GuruAgent>> =
        guruAgentDao.getAllAgentsFlow().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAllAgents(): List<GuruAgent> = withContext(Dispatchers.IO) {
        guruAgentDao.getAllAgents().map { it.toDomain() }
    }

    override suspend fun getEnabledAgents(): List<GuruAgent> = withContext(Dispatchers.IO) {
        guruAgentDao.getEnabledAgents().map { it.toDomain() }
    }

    override suspend fun getAgent(id: String): GuruAgent? = withContext(Dispatchers.IO) {
        guruAgentDao.getAgentById(id)?.toDomain()
    }

    override suspend fun resolveAgent(slugOrName: String): GuruAgent? = withContext(Dispatchers.IO) {
        val key = slugOrName.trim()
        if (key.isEmpty()) return@withContext null
        guruAgentDao.getAgentBySlug(key)?.toDomain()
            ?: guruAgentDao.getAgentByName(key)?.toDomain()
    }

    /**
     * Create an agent, giving it a real slug. Issue #233.
     *
     * THE SLUG IS DERIVED, NEVER ASKED FOR. A person naming their agent "coding agent"
     * does not know what a slug is and should never be shown one. It is derived from the
     * name by [AgentSlug], the same rule the publish path uses, so the identity the agent
     * carries locally is the identity it publishes under.
     *
     * IT USED TO BE HARDCODED TO "". That looked harmless because spawn resolves by name
     * as a fallback, which is why a created agent still ran. But an empty slug is a SHARED
     * key: anything that stores a slug — an automation step, a deep link, the skill library
     * installing by slug — either fails or collides with the next agent created. And the
     * server requires `slug NOT NULL UNIQUE`, so an empty slug could never publish.
     */
    override suspend fun createAgent(request: SaveAgentRequest): GuruAgent = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = GuruAgentEntity(
            id = Uuid.random().toString(),
            slug = AgentSlug.unique(request.name, guruAgentDao.getAllAgents().map { it.slug }),
            name = request.name.trim(),
            description = request.description.trim(),
            systemPrompt = request.systemPrompt,
            toolPermissions = request.toolPermissions.joinToString(", "),
            source = GuruAgent.SOURCE_USER,
            enabled = true,
            version = 1,
            createdAt = now,
            updatedAt = now,
            visualJson = AgentConfigurationCodec.encodeVisual(request.visual),
            visibilityJson = AgentConfigurationCodec.encodeVisibility(request.visibility),
            autonomyJson = AgentConfigurationCodec.encodeAutonomy(request.autonomy),
            budgetsJson = AgentConfigurationCodec.encodeBudgets(request.budgets),
            modelPreferenceJson = AgentConfigurationCodec.encodeModelPreference(request.modelPreference),
            notificationsJson = AgentConfigurationCodec.encodeNotifications(request.notifications),
            memoryScopeJson = AgentConfigurationCodec.encodeMemoryScope(request.memoryScope),
            contextScopeJson = AgentConfigurationCodec.encodeContextScope(request.contextScope),
            manifestJson = AgentConfigurationCodec.encodeManifest(request.manifest),
            skillIds = request.skillIds.joinToString(", "),
            /*
             * A NEW AGENT HAS NO LOCAL RESTRICTION. `SaveAgentRequest` does not carry one because a
             * restriction is a decision about an imported agent's published grant, made in the room
             * rather than in the creator, and a create has nothing to restrict.
             */
            toolNarrowing = "",
            // The first saved version. `definitionVersion` counts edits, so a create writes 1.
            definitionVersion = 1,
        )
        transactor.inTransaction {
            guruAgentDao.insertAgent(entity)
            definitionVersionDao.insert(
                versionRow(
                    entity = entity,
                    definitionVersion = 1,
                    reason = AgentDefinitionVersionEntity.REASON_CREATED,
                    author = AgentDefinitionVersionEntity.AUTHOR_USER,
                    savedAt = now,
                )
            )
        }
        entity.toDomain()
    }

    /**
     * Save an edit, and record the version it produced.
     *
     * THE VERSION COUNTER AND THE HISTORY ROW MOVE TOGETHER. Bumping `definitionVersion` without
     * writing the row leaves a number with no document behind it; writing the row without bumping
     * collides with the unique index on (agent_id, definition_version). One transaction makes the
     * pair indivisible.
     *
     * A SYNCS-WORKER UPDATE PASSES ITS OWN REASON. A version that arrived from the catalogue is not
     * an edit somebody made here, and the history should say which it was.
     */
    override suspend fun updateAgent(id: String, request: SaveAgentRequest): GuruAgent =
        withContext(Dispatchers.IO) {
            val existing = guruAgentDao.getAgentById(id)
                ?: throw IllegalArgumentException("Agent not found: $id")
            val now = System.currentTimeMillis()
            val nextVersion = existing.definitionVersion + 1
            val updated = existing.copy(
                name = request.name.trim(),
                description = request.description.trim(),
                systemPrompt = request.systemPrompt,
                toolPermissions = request.toolPermissions.joinToString(", "),
                visualJson = AgentConfigurationCodec.encodeVisual(request.visual),
                visibilityJson = AgentConfigurationCodec.encodeVisibility(request.visibility),
                autonomyJson = AgentConfigurationCodec.encodeAutonomy(request.autonomy),
                budgetsJson = AgentConfigurationCodec.encodeBudgets(request.budgets),
                modelPreferenceJson = AgentConfigurationCodec.encodeModelPreference(request.modelPreference),
                notificationsJson = AgentConfigurationCodec.encodeNotifications(request.notifications),
                memoryScopeJson = AgentConfigurationCodec.encodeMemoryScope(request.memoryScope),
                contextScopeJson = AgentConfigurationCodec.encodeContextScope(request.contextScope),
                manifestJson = AgentConfigurationCodec.encodeManifest(request.manifest),
                skillIds = request.skillIds.joinToString(", "),
                /*
                 * THE LOCAL RESTRICTION SURVIVES AN EDIT. It is not part of `SaveAgentRequest`, so a
                 * save that carried no opinion about it must leave it exactly as the person set it;
                 * clearing it here would silently hand an imported agent its full published grant
                 * back the next time anybody edited its prompt.
                 */
                toolNarrowing = existing.toolNarrowing,
                definitionVersion = nextVersion,
                updatedAt = now,
            )
            transactor.inTransaction {
                guruAgentDao.updateAgent(updated)
                definitionVersionDao.insert(
                    versionRow(
                        entity = updated,
                        definitionVersion = nextVersion,
                        reason = AgentDefinitionVersionEntity.REASON_EDITED,
                        author = AgentDefinitionVersionEntity.AUTHOR_USER,
                        savedAt = now,
                    )
                )
            }
            updated.toDomain()
        }

    /**
     * Change visibility, and only visibility.
     *
     * THE NARROWNESS IS THE FEATURE. The specification says changing visibility must not silently
     * change tool permissions, autonomy, memory access or execution authority, and the only way to
     * keep that true is for the write to touch one column. The creator's save request carries every
     * one of those, so routing this through it would mean the room saving whatever definition it was
     * holding — which is how a display preference becomes a permission change.
     *
     * IT IS STILL AN EDIT, so it appends a version: a run started afterwards records the new
     * visibility in its snapshot, and a run already in flight keeps the one it started with. What the
     * current run draws changes immediately, because display is read at draw time; what it captured
     * does not, because capture was decided when it started.
     */
    override suspend fun updateVisibility(
        id: String,
        visibility: com.unuslumen.app.domain.model.AgentVisibility,
    ): GuruAgent = withContext(Dispatchers.IO) {
        val existing = guruAgentDao.getAgentById(id)
            ?: throw IllegalArgumentException("Agent not found: $id")
        val now = System.currentTimeMillis()
        val nextVersion = existing.definitionVersion + 1
        val updated = existing.copy(
            visibilityJson = AgentConfigurationCodec.encodeVisibility(visibility),
            definitionVersion = nextVersion,
            updatedAt = now,
        )
        transactor.inTransaction {
            guruAgentDao.updateAgent(updated)
            definitionVersionDao.insert(
                versionRow(
                    entity = updated,
                    definitionVersion = nextVersion,
                    reason = AgentDefinitionVersionEntity.REASON_EDITED,
                    author = AgentDefinitionVersionEntity.AUTHOR_USER,
                    savedAt = now,
                )
            )
        }
        updated.toDomain()
    }

    /**
     * Hold an agent to fewer tools than it was published with.
     *
     * THE ONLY REFUSAL IS AN EXTENSION. A name outside the published grant is asking for something
     * the catalogue review never saw, so it cannot be allowed here; every other shape is accepted,
     * including an identical set, because a no-op restriction is not an error.
     *
     * A RESTRICTION THAT REMOVES NOTHING IS STORED AS EMPTY. Keeping a set identical to the published
     * grant would make the column claim a restriction exists when the effect is none, so the two
     * states are collapsed into the one that is true.
     */
    override suspend fun setToolNarrowing(id: String, tools: Set<String>): GuruAgent =
        withContext(Dispatchers.IO) {
            val existing = guruAgentDao.getAgentById(id)
                ?: throw IllegalArgumentException("Agent not found: $id")
            val published = existing.toolPermissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val verdict = com.unuslumen.app.domain.agents.AgentToolNarrowing.evaluate(
                publishedTools = published,
                requested = tools,
            )
            if (!verdict.permitted) {
                throw IllegalArgumentException(verdict.explanation ?: "That restriction cannot be applied.")
            }

            val stored = if (verdict.tools == published.toSet()) emptySet() else verdict.tools
            val now = System.currentTimeMillis()
            val nextVersion = existing.definitionVersion + 1
            val updated = existing.copy(
                toolNarrowing = stored.joinToString(", "),
                definitionVersion = nextVersion,
                updatedAt = now,
            )
            transactor.inTransaction {
                guruAgentDao.updateAgent(updated)
                definitionVersionDao.insert(
                    versionRow(
                        entity = updated,
                        definitionVersion = nextVersion,
                        reason = AgentDefinitionVersionEntity.REASON_EDITED,
                        author = AgentDefinitionVersionEntity.AUTHOR_USER,
                        savedAt = now,
                    )
                )
            }
            updated.toDomain()
        }

    override suspend fun enableAgent(id: String): GuruAgent = withContext(Dispatchers.IO) {
        guruAgentDao.setEnabled(id, true)
        guruAgentDao.getAgentById(id)?.toDomain()
            ?: throw IllegalArgumentException("Agent not found: $id")
    }

    override suspend fun disableAgent(id: String): GuruAgent = withContext(Dispatchers.IO) {
        guruAgentDao.setEnabled(id, false)
        guruAgentDao.getAgentById(id)?.toDomain()
            ?: throw IllegalArgumentException("Agent not found: $id")
    }

    override suspend fun deleteAgent(id: String) = withContext(Dispatchers.IO) {
        val existing = guruAgentDao.getAgentById(id)
            ?: throw IllegalArgumentException("Agent not found: $id")
        // A publisher-delivered copy is withdrawn by retraction, never by a
        // button, so a person cannot delete something the catalogue will hand
        // them again on the next sync and think the deletion stuck.
        if (existing.source == GuruAgent.SOURCE_SYNCED) {
            throw IllegalStateException(
                "This agent came from the catalogue, so it is removed when it is withdrawn rather than by hand."
            )
        }
        transactor.inTransaction {
            guruAgentDao.deleteAgent(id)
            /*
             * THE OWNER'S DELETE TAKES THE HISTORY WITH IT.
             *
             * This is the one place definition history is removed, and it is deliberate rather
             * than a cascade: a person deleting their own agent has asked for it to be gone,
             * whereas a catalogue retraction is somebody else withdrawing a publication and its
             * record of what ran is still evidence. A foreign key could not tell those apart.
             */
            definitionVersionDao.deleteHistoryForAgent(id)
        }
    }

    /**
     * The saved versions of one agent, newest first.
     *
     * Deliberately NOT part of the repository interface yet: nothing in the UI asks for it and
     * widening the interface before its consumer exists is how an abstraction ends up shaped by a
     * guess. The DAO is reachable for the tests that prove the history is written, and the read
     * lands on the interface when the settings panel that shows it is built.
     */
    suspend fun versionsFor(agentId: String): List<AgentDefinitionVersionEntity> =
        withContext(Dispatchers.IO) { definitionVersionDao.versionsForAgent(agentId) }

    override suspend fun getSummary(): AgentSummary = withContext(Dispatchers.IO) {
        val all = guruAgentDao.getAllAgents()
        AgentSummary(
            total = all.size,
            enabled = all.count { it.enabled },
            userMade = all.count { it.source == GuruAgent.SOURCE_USER },
            downloaded = all.count { it.source == GuruAgent.SOURCE_SYNCED }
        )
    }

    override suspend fun isNameAvailable(name: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        trimmed.isNotEmpty() && guruAgentDao.getAgentByName(trimmed) == null
    }

    override suspend fun installSyncedAgent(
        slug: String,
        name: String,
        description: String,
        systemPrompt: String,
        toolPermissions: List<String>,
        version: Int
    ): GuruAgent = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = GuruAgentEntity(
            id = Uuid.random().toString(),
            slug = slug,
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            toolPermissions = toolPermissions.joinToString(", "),
            source = GuruAgent.SOURCE_SYNCED,
            enabled = true,
            version = version,
            createdAt = now,
            updatedAt = now,
            // A catalogue agent arrives with the publisher's configuration or none. The JSON
            // columns keep their defaults, which decode to the same transparent, default-autonomy
            // agent a locally created one starts as, and the manifest is derived from what it
            // declares rather than invented here.
            definitionVersion = 1,
        )
        transactor.inTransaction {
            guruAgentDao.insertAgent(entity)
            definitionVersionDao.insert(
                versionRow(
                    entity = entity,
                    definitionVersion = 1,
                    reason = AgentDefinitionVersionEntity.REASON_SYNCED,
                    author = AgentDefinitionVersionEntity.AUTHOR_CATALOGUE,
                    savedAt = now,
                )
            )
            // One live copy per name. The freshly landed row stays enabled; every
            // other synced row of the same name is retired so a call by name
            // resolves to exactly one agent. User rows are never candidates here.
            for (stale in guruAgentDao.getSyncedAgents().filter { it.name == name && it.id != entity.id }) {
                guruAgentDao.setEnabled(stale.id, false)
            }
        }
        entity.toDomain()
    }

    override suspend fun getSyncedAgents(): List<GuruAgent> = withContext(Dispatchers.IO) {
        guruAgentDao.getSyncedAgents().map { it.toDomain() }
    }

    override suspend fun retractSyncedAgentsNotIn(survivingSlugs: Set<String>): Int =
        withContext(Dispatchers.IO) {
            var withdrawn = 0
            for (row in guruAgentDao.getSyncedAgents()) {
                if (row.slug.isBlank() || row.slug !in survivingSlugs) {
                    /*
                     * A RETRACTION REMOVES THE ROW BUT KEEPS THE HISTORY.
                     *
                     * The publication is gone, so the agent can no longer be called. What it was,
                     * and therefore what any run of it was given, is still evidence and survives.
                     * That is the opposite of the owner's own delete above, and the difference is
                     * who asked.
                     */
                    guruAgentDao.deleteAgent(row.id)
                    withdrawn++
                }
            }
            withdrawn
        }

    /** One history row for a definition, serialised from the entity as it will be stored. */
    private fun versionRow(
        entity: GuruAgentEntity,
        definitionVersion: Int,
        reason: String,
        author: String,
        savedAt: Long,
    ): AgentDefinitionVersionEntity = AgentDefinitionVersionEntity(
        versionRowId = Uuid.random().toString(),
        agentId = entity.id,
        slug = entity.slug,
        name = entity.name,
        definitionVersion = definitionVersion,
        description = entity.description,
        /*
         * THE WHOLE DEFINITION, AS THE DOMAIN DOCUMENT IT IS.
         *
         * Serialised from `GuruAgent.definitionSnapshot()` rather than from the entity, because the
         * snapshot type is the one with a schema contract and a decode path. The entity is a table
         * row whose shape may change; the snapshot is what a run also records, so history and a run
         * describe a definition in exactly the same terms rather than in two slightly different
         * ones. It also means the sealed JSON columns are already decoded values here, not nested
         * documents that would need unwrapping later.
         */
        definitionJson = AgentConfigurationCodec.encodeDefinition(entity.toDomain()),
        reason = reason,
        author = author,
        savedAt = savedAt,
    )

    private fun GuruAgentEntity.toDomain(): GuruAgent {
        val tools = if (toolPermissions.isBlank()) {
            emptyList()
        } else {
            toolPermissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        val skills = if (skillIds.isBlank()) {
            emptyList()
        } else {
            skillIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        /*
         * AN EMPTY NARROWING MEANS NO RESTRICTION, NOT NO TOOLS. Every existing row has an empty
         * column, and reading it as "this agent may use nothing" would break every catalogue agent on
         * the phone on upgrade.
         */
        val narrowing = if (toolNarrowing.isBlank()) {
            emptySet()
        } else {
            toolNarrowing.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        return GuruAgent(
            id = id,
            slug = slug,
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            toolPermissions = tools,
            source = source,
            enabled = enabled,
            version = version,
            createdAt = createdAt,
            updatedAt = updatedAt,
            visual = AgentConfigurationCodec.decodeVisual(visualJson),
            visibility = AgentConfigurationCodec.decodeVisibility(visibilityJson),
            autonomy = AgentConfigurationCodec.decodeAutonomy(autonomyJson),
            budgets = AgentConfigurationCodec.decodeBudgets(budgetsJson),
            modelPreference = AgentConfigurationCodec.decodeModelPreference(modelPreferenceJson),
            notifications = AgentConfigurationCodec.decodeNotifications(notificationsJson),
            skillIds = skills,
            toolNarrowing = narrowing,
            memoryScope = AgentConfigurationCodec.decodeMemoryScope(memoryScopeJson),
            contextScope = AgentConfigurationCodec.decodeContextScope(contextScopeJson),
            manifest = AgentConfigurationCodec.decodeManifest(manifestJson),
            definitionVersion = definitionVersion,
        )
    }

    /** The frozen definition values a run records at creation. */
    fun snapshotOf(agent: GuruAgent): AgentDefinitionSnapshot = agent.definitionSnapshot()
}
