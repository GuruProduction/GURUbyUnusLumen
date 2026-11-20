// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.AgentRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * The run rows: creation, the materialised projection, and the lease.
 *
 * A RUN IS MUTABLE, WHICH IS WHY EVERY WRITE HERE IS NARROW. There is no general
 * update method, because a general update is how a status gets set without the event
 * that explains it. Each function changes exactly one fact and is called from inside a
 * transaction that also appends the event describing it.
 *
 * THE LEASE WRITES ARE CONDITIONAL ON PURPOSE. `claimLease` and `heartbeat` both carry
 * a `WHERE lease_owner = :owner` (or `IS NULL` for the claim), so a process that has
 * lost its lease cannot renew it by writing late. Two processes cannot both believe
 * they own the same run, because the database refuses the second update.
 */
@Dao
interface AgentRunDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE run_id = :runId")
    suspend fun run(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE run_id = :runId")
    fun observeRun(runId: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_runs WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    suspend fun runsForConversation(conversationId: String): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    fun observeRunsForConversation(conversationId: String): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs WHERE agent_id = :agentId ORDER BY created_at DESC LIMIT :limit")
    suspend fun runsForAgent(agentId: String, limit: Int = 50): List<AgentRunEntity>

    /**
     * Every run that named this one as its parent.
     *
     * THE CHAIN IS ONE INDEXED QUERY. A delegated run carries `parent_run_id`, so a parent finds its
     * children without a second table. The fan-out ceiling is counted from these real rows rather
     * than from a number the parent reported about itself, because a parent that could count its own
     * children could under-report them.
     */
    @Query("SELECT * FROM agent_runs WHERE parent_run_id = :parentRunId ORDER BY created_at ASC")
    suspend fun childrenOf(parentRunId: String): List<AgentRunEntity>

    /**
     * Every run the recovery scan must consider.
     *
     * NON-TERMINAL ONLY. A finished run needs no recovery, and scanning thousands of
     * them on every launch would make the check cost more than the thing it protects.
     */
    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status IN ('CREATED','STARTING','RUNNING','WAITING_FOR_GURU','WAITING_FOR_USER','WAITING_FOR_TOOL','PAUSED','INTEGRATING','INTERRUPTED')
        ORDER BY updated_at ASC
        """
    )
    suspend fun nonTerminalRuns(): List<AgentRunEntity>

    /** The runs whose owner may have died: not waiting, and holding a lease that has gone quiet. */
    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status NOT IN ('COMPLETED','LIMIT_REACHED','FAILED','CANCELLED','WAITING_FOR_GURU','WAITING_FOR_USER')
          AND lease_owner IS NOT NULL
          AND (lease_heartbeat_at IS NULL OR lease_heartbeat_at < :staleBefore)
        ORDER BY updated_at ASC
        """
    )
    suspend fun leaseSuspects(staleBefore: Long): List<AgentRunEntity>

    /** Advance the event allocation counter. Always called inside the inserting transaction. */
    @Query("UPDATE agent_runs SET last_event_sequence = :sequence, updated_at = :now WHERE run_id = :runId")
    suspend fun setLastEventSequence(runId: String, sequence: Long, now: Long)

    @Query("UPDATE agent_runs SET last_checkpoint_sequence = :sequence, updated_at = :now WHERE run_id = :runId")
    suspend fun setLastCheckpointSequence(runId: String, sequence: Long, now: Long)

    /** The materialised state, always committed with its RUN_STATE_CHANGED event. */
    @Query(
        """
        UPDATE agent_runs
        SET status = :status, updated_at = :now, started_at = COALESCE(started_at, :startedAt),
            finished_at = :finishedAt
        WHERE run_id = :runId
        """
    )
    suspend fun setStatus(
        runId: String,
        status: String,
        now: Long,
        startedAt: Long? = null,
        finishedAt: Long? = null,
    )

    @Query("UPDATE agent_runs SET result_summary = :summary, updated_at = :now WHERE run_id = :runId")
    suspend fun setResultSummary(runId: String, summary: String?, now: Long)

    @Query("UPDATE agent_runs SET failure_code = :code, failure_message = :message, updated_at = :now WHERE run_id = :runId")
    suspend fun setFailure(runId: String, code: String?, message: String?, now: Long)

    // ---------------- lease ----------------

    /**
     * Take the lease, or take it back from a dead owner.
     *
     * Returns the number of rows changed: 1 means this caller now owns the run, 0 means
     * somebody else does. Conditional in SQL rather than in Kotlin, because a
     * read-then-write in application code has a window between the two where a second
     * process can slip in.
     */
    @Query(
        """
        UPDATE agent_runs
        SET lease_owner = :owner, lease_heartbeat_at = :now
        WHERE run_id = :runId AND (lease_owner IS NULL OR lease_owner = :previousOwner)
        """
    )
    suspend fun claimLease(runId: String, owner: String, previousOwner: String?, now: Long): Int

    /** Renew, but only while this caller still holds it. A late write from a dead owner changes nothing. */
    @Query(
        """
        UPDATE agent_runs
        SET lease_heartbeat_at = :now
        WHERE run_id = :runId AND lease_owner = :owner
        """
    )
    suspend fun heartbeat(runId: String, owner: String, now: Long): Int

    /** Drop the lease. Used when a run enters a waiting state: waiting is not dying. */
    @Query(
        """
        UPDATE agent_runs
        SET lease_owner = NULL, lease_heartbeat_at = NULL
        WHERE run_id = :runId AND lease_owner = :owner
        """
    )
    suspend fun releaseLease(runId: String, owner: String): Int

    /** Force the lease away from an owner the scan has proved dead. */
    @Query(
        """
        UPDATE agent_runs
        SET lease_owner = NULL, lease_heartbeat_at = NULL
        WHERE run_id = :runId AND lease_owner = :deadOwner
        """
    )
    suspend fun releaseDeadLease(runId: String, deadOwner: String): Int

    @Query("DELETE FROM agent_runs WHERE run_id = :runId")
    suspend fun deleteRun(runId: String)

    @Update
    suspend fun updateRun(run: AgentRunEntity)
}
