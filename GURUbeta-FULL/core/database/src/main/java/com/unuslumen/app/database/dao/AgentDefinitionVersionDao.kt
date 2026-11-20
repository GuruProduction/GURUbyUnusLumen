// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentDefinitionVersionEntity

/**
 * The definition history.
 *
 * APPEND ONLY, like the run ledger and for the same reason. A version that can be edited is not a
 * version, it is a second copy of the current state that happens to have an older timestamp. There
 * is no update and no delete here; the only way a wrong version stops being current is a later
 * version superseding it.
 *
 * The one delete that exists is scoped by agent id, for when somebody deletes an agent and asks for
 * its history to go too. That is the owner's decision and it is explicit rather than a cascade.
 */
@Dao
interface AgentDefinitionVersionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: AgentDefinitionVersionEntity)

    /** Every saved version of one agent, newest first. The history panel reads this. */
    @Query(
        "SELECT * FROM agent_definition_versions WHERE agent_id = :agentId " +
            "ORDER BY definition_version DESC"
    )
    suspend fun versionsForAgent(agentId: String): List<AgentDefinitionVersionEntity>

    /** One specific version, for restoring or diffing. */
    @Query(
        "SELECT * FROM agent_definition_versions WHERE agent_id = :agentId " +
            "AND definition_version = :definitionVersion LIMIT 1"
    )
    suspend fun version(agentId: String, definitionVersion: Int): AgentDefinitionVersionEntity?

    /** The newest version, which is what the live row should match after a consistent save. */
    @Query(
        "SELECT * FROM agent_definition_versions WHERE agent_id = :agentId " +
            "ORDER BY definition_version DESC LIMIT 1"
    )
    suspend fun latestVersion(agentId: String): AgentDefinitionVersionEntity?

    @Query("SELECT COUNT(*) FROM agent_definition_versions WHERE agent_id = :agentId")
    suspend fun countForAgent(agentId: String): Int

    /**
     * Drop one agent's history.
     *
     * ONLY WHEN THE OWNER DELETES THE AGENT, never on a catalogue retraction: a withdrawn agent's
     * history is still evidence of what ran. This is a deliberate call at the delete site rather
     * than a foreign key that fires whether or not anybody meant it.
     */
    @Query("DELETE FROM agent_definition_versions WHERE agent_id = :agentId")
    suspend fun deleteHistoryForAgent(agentId: String)
}
