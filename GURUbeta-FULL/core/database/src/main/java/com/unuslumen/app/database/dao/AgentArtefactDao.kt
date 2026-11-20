// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentArtefactEntity
import kotlinx.coroutines.flow.Flow

/**
 * The register of what agents produced.
 *
 * THE POINT IS THE LINK, not the storage. Every query here exists to answer "which run made this"
 * or "what did this run make", in both directions.
 */
@Dao
interface AgentArtefactDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artefact: AgentArtefactEntity)

    @Query("SELECT * FROM agent_artefacts WHERE run_id = :runId ORDER BY created_at ASC")
    suspend fun forRun(runId: String): List<AgentArtefactEntity>

    /** Live view for a room watching a run produce things. */
    @Query("SELECT * FROM agent_artefacts WHERE run_id = :runId ORDER BY created_at ASC")
    fun forRunFlow(runId: String): Flow<List<AgentArtefactEntity>>

    /** Everything one agent has ever made, newest first. What its history shows. */
    @Query("SELECT * FROM agent_artefacts WHERE agent_id = :agentId ORDER BY created_at DESC")
    suspend fun forAgent(agentId: String): List<AgentArtefactEntity>

    @Query("SELECT * FROM agent_artefacts WHERE artefact_id = :artefactId")
    suspend fun artefact(artefactId: String): AgentArtefactEntity?

    /** Every artefact pointing at one storage object, so a delete can find what cites it. */
    @Query(
        "SELECT * FROM agent_artefacts WHERE storage_kind = :storageKind AND storage_ref = :storageRef"
    )
    suspend fun citing(storageKind: String, storageRef: String): List<AgentArtefactEntity>

    @Query("SELECT * FROM agent_artefacts WHERE kind = :kind ORDER BY created_at DESC")
    suspend fun byKind(kind: String): List<AgentArtefactEntity>

    @Query("SELECT COUNT(*) FROM agent_artefacts WHERE run_id = :runId")
    suspend fun countForRun(runId: String): Int
}
