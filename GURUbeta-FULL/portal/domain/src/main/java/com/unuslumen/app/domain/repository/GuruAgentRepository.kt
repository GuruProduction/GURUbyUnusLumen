// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.AgentSummary
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.model.SaveAgentRequest
import kotlinx.coroutines.flow.Flow

/**
 * The agent store. One table, three sources, and a hard rule that a person's own
 * agents can never be overwritten or withdrawn by the catalogue.
 */
interface GuruAgentRepository {

    fun getAllAgentsFlow(): Flow<List<GuruAgent>>

    suspend fun getAllAgents(): List<GuruAgent>

    suspend fun getEnabledAgents(): List<GuruAgent>

    suspend fun getAgent(id: String): GuruAgent?

    /** Resolve by catalogue slug first, then by display name, so a call site can name either. */
    suspend fun resolveAgent(slugOrName: String): GuruAgent?

    suspend fun createAgent(request: SaveAgentRequest): GuruAgent

    suspend fun updateAgent(id: String, request: SaveAgentRequest): GuruAgent

    suspend fun enableAgent(id: String): GuruAgent

    suspend fun disableAgent(id: String): GuruAgent

    /**
     * Delete one agent. Refuses a synced agent, because withdrawal of a
     * publisher-delivered copy is the sync worker's decision, not a button.
     */
    suspend fun deleteAgent(id: String)

    suspend fun getSummary(): AgentSummary

    suspend fun isNameAvailable(name: String): Boolean

    /**
     * Land a publisher-delivered agent. Insert-not-upsert so every landed
     * version is a fresh row, then every other synced row of the same name is
     * disabled for the one-live-copy guarantee. Never touches a user row.
     */
    suspend fun installSyncedAgent(
        slug: String,
        name: String,
        description: String,
        systemPrompt: String,
        toolPermissions: List<String>,
        version: Int
    ): GuruAgent

    suspend fun getSyncedAgents(): List<GuruAgent>

    /**
     * Retraction: remove every synced row whose slug no longer appears in the
     * catalogue. The caller supplies the surviving slugs. User rows are never
     * candidates.
     */
    suspend fun retractSyncedAgentsNotIn(survivingSlugs: Set<String>): Int

    /**
     * The tools an agent may be granted, as name, category and description.
     *
     * Read through the repository rather than the tool registry directly so the
     * creator screen stays inside the domain layer it is allowed to see. The
     * agent verbs are already excluded, because an agent may never be granted
     * the ability to spawn another.
     */
    suspend fun grantableTools(): List<GrantableTool>
}

/** One entry in the creator's tool picker. */
data class GrantableTool(
    val name: String,
    val category: String,
    val description: String
)
