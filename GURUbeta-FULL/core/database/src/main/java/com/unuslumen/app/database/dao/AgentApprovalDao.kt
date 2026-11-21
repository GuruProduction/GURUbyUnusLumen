// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentApprovalEntity
import kotlinx.coroutines.flow.Flow

/**
 * Pending and decided approval requests.
 *
 * THE DECISION UPDATE IS CONDITIONAL ON THE STATE STILL BEING PENDING. Two taps on Yes — or one tap
 * and a race with a cancellation — must produce one decision, and the second must change nothing
 * rather than overwriting the first with a different outcome.
 */
@Dao
interface AgentApprovalDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(approval: AgentApprovalEntity)

    @Query("SELECT * FROM agent_approvals WHERE approval_id = :approvalId")
    suspend fun approval(approvalId: String): AgentApprovalEntity?

    /** Everything a run has asked for, newest first. */
    @Query("SELECT * FROM agent_approvals WHERE run_id = :runId ORDER BY requested_at DESC")
    suspend fun forRun(runId: String): List<AgentApprovalEntity>

    /** What is still waiting on a person, oldest first so the oldest is answered first. */
    @Query(
        "SELECT * FROM agent_approvals WHERE state = 'PENDING' ORDER BY requested_at ASC"
    )
    suspend fun pending(): List<AgentApprovalEntity>

    @Query(
        "SELECT * FROM agent_approvals WHERE run_id = :runId AND state = 'PENDING' " +
            "ORDER BY requested_at ASC"
    )
    suspend fun pendingForRun(runId: String): List<AgentApprovalEntity>

    /** Live view of everything pending, for a surface that shows what needs deciding. */
    @Query("SELECT * FROM agent_approvals WHERE state = 'PENDING' ORDER BY requested_at ASC")
    fun pendingFlow(): Flow<List<AgentApprovalEntity>>

    /**
     * Decide, and only if it is still undecided.
     *
     * Returns 1 when this call made the decision and 0 when somebody already had. The caller must
     * treat 0 as "already decided" rather than as a failure, because that is exactly what it is.
     */
    @Query(
        "UPDATE agent_approvals SET state = :state, decided_by = :decidedBy, " +
            "decided_at = :decidedAt, decision_note = :note " +
            "WHERE approval_id = :approvalId AND state = 'PENDING'"
    )
    suspend fun decide(
        approvalId: String,
        state: String,
        decidedBy: String,
        decidedAt: Long,
        note: String?,
    ): Int

    /**
     * Spend a granted approval.
     *
     * CONDITIONAL ON THE STATE STILL BEING APPROVED, and that condition is the whole point. `decide`
     * above only fires against a PENDING row, so reusing it to consume a grant silently did nothing:
     * the row stayed APPROVED and the same yes authorised every later call of the same kind. The test
     * that caught this was written against a real database, which is the only place the difference
     * between "the write ran" and "the write changed a row" is observable.
     */
    @Query(
        "UPDATE agent_approvals SET state = 'CANCELLED', decided_by = :by, " +
            "decided_at = :at, decision_note = :note " +
            "WHERE approval_id = :approvalId AND state = 'APPROVED'"
    )
    suspend fun consumeGrant(approvalId: String, by: String, at: Long, note: String): Int

    @Query("SELECT COUNT(*) FROM agent_approvals WHERE state = 'PENDING'")
    suspend fun pendingCount(): Int
}
