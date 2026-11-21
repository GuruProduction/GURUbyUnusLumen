// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentContextReferenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * What an agent was given, and what it was merely offered.
 *
 * THE TWO LISTS ARE DIFFERENT AND BOTH MATTER. Everything retrieved is evidence of what the search
 * found; only the supplied subset is evidence of what influenced the answer. A provenance panel
 * that showed only the second would hide a retrieval that failed to deliver, and one that showed
 * only the first would claim the agent read things it never saw.
 */
@Dao
interface AgentContextReferenceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(reference: AgentContextReferenceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(references: List<AgentContextReferenceEntity>)

    /** Everything considered for one run, in retrieval order. */
    @Query("SELECT * FROM agent_context_references WHERE run_id = :runId ORDER BY created_at ASC")
    suspend fun forRun(runId: String): List<AgentContextReferenceEntity>

    @Query("SELECT * FROM agent_context_references WHERE run_id = :runId ORDER BY created_at ASC")
    fun forRunFlow(runId: String): Flow<List<AgentContextReferenceEntity>>

    /** Only what actually reached the model. The honest influence list. */
    @Query(
        "SELECT * FROM agent_context_references WHERE run_id = :runId AND supplied_to_model = 1 " +
            "ORDER BY created_at ASC"
    )
    suspend fun suppliedToModel(runId: String): List<AgentContextReferenceEntity>

    /** The candidates that did not fit. Kept so a truncated retrieval is visible rather than silent. */
    @Query(
        "SELECT * FROM agent_context_references WHERE run_id = :runId AND supplied_to_model = 0 " +
            "ORDER BY created_at ASC"
    )
    suspend fun notSupplied(runId: String): List<AgentContextReferenceEntity>

    /**
     * Mark a candidate as having reached the model.
     *
     * CONDITIONAL ON THE RUN, so a caller holding a reference id cannot flip a row belonging to
     * another execution. Returns 1 when it changed a row and 0 when the pair did not match.
     */
    @Query(
        "UPDATE agent_context_references SET supplied_to_model = 1 " +
            "WHERE reference_id = :referenceId AND run_id = :runId"
    )
    suspend fun markSupplied(referenceId: String, runId: String): Int

    /** Every run that cited one source, so a person can see where a memory was used. */
    @Query(
        "SELECT * FROM agent_context_references WHERE source_kind = :sourceKind " +
            "AND source_ref = :sourceRef ORDER BY created_at DESC"
    )
    suspend fun citationsOf(sourceKind: String, sourceRef: String): List<AgentContextReferenceEntity>

    @Query("SELECT COUNT(*) FROM agent_context_references WHERE run_id = :runId")
    suspend fun countForRun(runId: String): Int
}
