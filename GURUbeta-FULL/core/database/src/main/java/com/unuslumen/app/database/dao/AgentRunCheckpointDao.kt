// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentRunCheckpointEntity

/**
 * Recovery points. Insert only.
 *
 * The newest checkpoint is where recovery starts: restore its state, then replay every
 * event above its `event_sequence`. Nothing here is ever updated, because an edited
 * recovery point is not a recovery point.
 */
@Dao
interface AgentRunCheckpointDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(checkpoint: AgentRunCheckpointEntity)

    @Query("SELECT * FROM agent_run_checkpoints WHERE checkpoint_id = :checkpointId")
    suspend fun checkpoint(checkpointId: String): AgentRunCheckpointEntity?

    /** The one recovery restores from. Null means the run has never checkpointed. */
    @Query(
        """
        SELECT * FROM agent_run_checkpoints
        WHERE run_id = :runId
        ORDER BY checkpoint_sequence DESC
        LIMIT 1
        """
    )
    suspend fun latestForRun(runId: String): AgentRunCheckpointEntity?

    @Query("SELECT * FROM agent_run_checkpoints WHERE run_id = :runId ORDER BY checkpoint_sequence ASC")
    suspend fun checkpointsForRun(runId: String): List<AgentRunCheckpointEntity>

    @Query("SELECT MAX(checkpoint_sequence) FROM agent_run_checkpoints WHERE run_id = :runId")
    suspend fun highestSequence(runId: String): Long?

    @Query(
        """
        SELECT * FROM agent_run_checkpoints
        WHERE run_id = :runId AND checkpoint_sequence = :sequence
        """
    )
    suspend fun checkpointAt(runId: String, sequence: Long): AgentRunCheckpointEntity?

    @Query("SELECT COUNT(*) FROM agent_run_checkpoints WHERE run_id = :runId")
    suspend fun countForRun(runId: String): Int
}
