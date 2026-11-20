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

    /**
     * Change one agent's visibility, and nothing else.
     *
     * WHY THIS IS NOT A `SaveAgentRequest`. The specification is explicit that changing visibility
     * must not silently change tool permissions, autonomy, memory access or execution authority, and
     * the creator's save request carries all of those. Routing a visibility change through it would
     * mean the room writes a whole definition from whatever values it happened to be holding, which
     * is how a display preference quietly becomes a permission change.
     *
     * SO THIS READS THE AGENT, REPLACES ONE FIELD, AND SAVES. Everything else is carried through
     * exactly as it was found, and the change is still an edit, so it appends a definition version
     * and a run started afterwards is configured with the new visibility while a run already in
     * flight keeps its snapshot.
     *
     * THE CHANGE APPLIES TO FUTURE RUNS IMMEDIATELY AND TO THE CURRENT ONE ONLY IN WHAT IS DRAWN.
     * Capture for a run in flight was decided when it started and is recorded per run, so turning a
     * category off now hides it here and cannot retroactively empty a record that already exists.
     */
    suspend fun updateVisibility(id: String, visibility: com.unuslumen.app.domain.model.AgentVisibility): GuruAgent

    /**
     * Hold an agent to fewer tools than it was published with, and nothing else.
     *
     * A LOCAL RESTRICTION, NEVER AN EXTENSION. The published grant is the ceiling, so a name outside
     * it is refused rather than stored. This is the only way section 13's isolation of imported
     * agents can be true for a person who wants one for a single job.
     *
     * IT DOES NOT REWRITE THE PUBLISHED GRANT. That list is what a later catalogue version is diffed
     * against, and the diff is what stops an update smuggling in a new permission; editing it would
     * erase the comparison. An empty set removes the restriction and restores everything published.
     */
    suspend fun setToolNarrowing(id: String, tools: Set<String>): GuruAgent
}

/** One entry in the creator's tool picker. */
data class GrantableTool(
    val name: String,
    val category: String,
    val description: String
)
