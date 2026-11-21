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

    /**
     * The most recent runs whose time is over, newest first.
     *
     * A LISTING NEEDS FINISHED RUNS, NOT ONLY LIVE ONES. `nonTerminalRuns` answers "what is
     * happening" and this answers "what happened", and a caller that can only see the first cannot
     * tell a completed run from one that never existed once it stops.
     */
    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status IN ('COMPLETED','LIMIT_REACHED','FAILED','CANCELLED')
        ORDER BY updated_at DESC LIMIT :limit
        """
    )
    suspend fun recentTerminalRuns(limit: Int): List<AgentRunEntity>

    /** The same, narrowed to one agent, so a listing about one agent does not read every run. */
    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status IN ('COMPLETED','LIMIT_REACHED','FAILED','CANCELLED')
          AND (agent_id = :agent OR agent_slug = :agent)
        ORDER BY updated_at DESC LIMIT :limit
        """
    )
    suspend fun recentTerminalRunsForAgent(agent: String, limit: Int): List<AgentRunEntity>

    /**
     * Every run that is still alive, as it changes.
     *
     * WHAT THE PORTAL'S GLOW READS. `nonTerminalRuns` is a one-shot query for the recovery scan; a
     * glow must appear when work starts and disappear when it ends, which needs a stream. The status
     * set is the same one recovery considers alive, so the indicator and the recovery scan cannot
     * disagree about whether a run is still around.
     */
    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status IN ('CREATED','STARTING','RUNNING','WAITING_FOR_GURU','WAITING_FOR_USER','WAITING_FOR_TOOL','PAUSED','INTEGRATING','INTERRUPTED')
        ORDER BY updated_at DESC
        """
    )
    fun observeActiveRuns(): Flow<List<AgentRunEntity>>

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

    /**
     * Move the run to a new state, but ONLY from a state it is allowed to leave.
     *
     * THIS IS THE RACE THAT `setStatus` LOST. The unconditional version could not tell a legitimate
     * transition from a late write by a dying coroutine, so a cancellation and a completion could
     * both write and the last one won. The ledger then held two contradictory state changes and the
     * row agreed with whichever arrived second, which is how a cancelled run comes back to life.
     *
     * The condition is in SQL, not in Kotlin, because a read-then-write has a window between the two
     * where another writer slips in. `0` rows changed means the run is no longer in a state this
     * transition is allowed from, and the caller must treat that as "somebody else already decided".
     *
     * THE LEASE IS RELEASED IN THE SAME STATEMENT for a terminal move. A terminal run holds no lease:
     * leaving one behind makes a dead run look alive to the recovery scan, and a separate release is
     * another write that can be skipped by a crash.
     */
    @Query(
        """
        UPDATE agent_runs
        SET status = :status,
            updated_at = :now,
            started_at = COALESCE(started_at, :startedAt),
            finished_at = :finishedAt,
            result_summary = COALESCE(:resultSummary, result_summary),
            lease_owner = CASE WHEN :clearLease = 1 THEN NULL ELSE lease_owner END,
            lease_heartbeat_at = CASE WHEN :clearLease = 1 THEN NULL ELSE lease_heartbeat_at END
        WHERE run_id = :runId AND status = :fromStatus
        """
    )
    suspend fun transitionStatus(
        runId: String,
        fromStatus: String,
        status: String,
        now: Long,
        startedAt: Long? = null,
        finishedAt: Long? = null,
        resultSummary: String? = null,
        clearLease: Int = 0,
    ): Int

    /**
     * Every run that looks like it should be executing and may not be.
     *
     * WHAT BELONGS HERE, AND WHAT DELIBERATELY DOES NOT. The set is "states in which something is
     * supposed to be driving this run", because those are the only ones where its absence is a
     * fault. Four states are excluded, each for its own reason:
     *
     *  - `WAITING_FOR_GURU` and `WAITING_FOR_USER` are waiting on a person. Nothing should be
     *    driving them and their lease was released on purpose.
     *  - `PAUSED` is a deliberate stop that keeps its state. Interrupting it would destroy work a
     *    person chose to keep, and it is resumable exactly as it stands.
     *  - `INTERRUPTED` is already the outcome this query exists to produce, so re-deriving it every
     *    scan would append the same fact for ever.
     *
     * IT INCLUDES RUNS THAT NEVER GOT A LEASE, which is the orphan this closes: a run created and
     * then lost before the host ever claimed it has no owner to go stale, so the older query (which
     * required `lease_owner IS NOT NULL`) never saw it and it sat at CREATED for ever.
     */
    @Query(
        """
        SELECT * FROM agent_runs
        WHERE status IN ('CREATED','STARTING','RUNNING','WAITING_FOR_TOOL','INTEGRATING')
          AND (lease_heartbeat_at IS NULL OR lease_heartbeat_at < :staleBefore)
          AND updated_at < :staleBefore
        ORDER BY updated_at ASC
        """
    )
    suspend fun leaseSuspects(staleBefore: Long): List<AgentRunEntity>

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
