// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.database.entity.GuruAgentEntity
import com.unuslumen.app.domain.model.AgentSummary
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
 */
@OptIn(ExperimentalUuidApi::class)
@Single(binds = [GuruAgentRepository::class])
class GuruAgentRepositoryImpl(
    private val guruAgentDao: GuruAgentDao,
    private val toolRegistry: com.unuslumen.app.data.tools.registry.ToolRegistry
) : GuruAgentRepository {

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

    override suspend fun createAgent(request: SaveAgentRequest): GuruAgent = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = GuruAgentEntity(
            id = Uuid.random().toString(),
            slug = "",
            name = request.name.trim(),
            description = request.description.trim(),
            systemPrompt = request.systemPrompt,
            toolPermissions = request.toolPermissions.joinToString(", "),
            source = GuruAgent.SOURCE_USER,
            enabled = true,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        guruAgentDao.insertAgent(entity)
        entity.toDomain()
    }

    override suspend fun updateAgent(id: String, request: SaveAgentRequest): GuruAgent =
        withContext(Dispatchers.IO) {
            val existing = guruAgentDao.getAgentById(id)
                ?: throw IllegalArgumentException("Agent not found: $id")
            val updated = existing.copy(
                name = request.name.trim(),
                description = request.description.trim(),
                systemPrompt = request.systemPrompt,
                toolPermissions = request.toolPermissions.joinToString(", "),
                updatedAt = System.currentTimeMillis()
            )
            guruAgentDao.updateAgent(updated)
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
        guruAgentDao.deleteAgent(id)
    }

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
            updatedAt = now
        )
        guruAgentDao.insertAgent(entity)

        // One live copy per name. The freshly landed row stays enabled; every
        // other synced row of the same name is retired so a call by name
        // resolves to exactly one agent. User rows are never candidates here.
        for (stale in guruAgentDao.getSyncedAgents().filter { it.name == name && it.id != entity.id }) {
            guruAgentDao.setEnabled(stale.id, false)
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
                    guruAgentDao.deleteAgent(row.id)
                    withdrawn++
                }
            }
            withdrawn
        }

    private fun GuruAgentEntity.toDomain(): GuruAgent {
        val tools = if (toolPermissions.isBlank()) {
            emptyList()
        } else {
            toolPermissions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
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
            updatedAt = updatedAt
        )
    }
}
