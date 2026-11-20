// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.AgentScheduleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Standing instructions, and the query that finds the ones that are due.
 *
 * THE DUE QUERY IS THE WHOLE POINT of the table. It asks for armed schedules whose next due time has
 * passed, in the order they became due, so a runner that wakes up after a gap works through them
 * oldest-first rather than in whatever order the rows happen to sit.
 *
 * EVERY WRITE IS NARROW AND CONDITIONAL WHERE IT MATTERS. Starting a run advances the schedule only
 * if the due time has not already moved, which is what stops two runners, or one runner and a
 * restart, from starting the same window twice.
 */
@Dao
interface AgentScheduleDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(schedule: AgentScheduleEntity)

    @Query("SELECT * FROM agent_schedules WHERE schedule_id = :scheduleId")
    suspend fun schedule(scheduleId: String): AgentScheduleEntity?

    @Query("SELECT * FROM agent_schedules ORDER BY enabled DESC, next_due_at ASC")
    suspend fun all(): List<AgentScheduleEntity>

    @Query("SELECT * FROM agent_schedules ORDER BY enabled DESC, next_due_at ASC")
    fun allFlow(): Flow<List<AgentScheduleEntity>>

    /** The schedules for one agent, so a room can show what will start it on its own. */
    @Query("SELECT * FROM agent_schedules WHERE agent_id = :agentId ORDER BY next_due_at ASC")
    suspend fun forAgent(agentId: String): List<AgentScheduleEntity>

    /**
     * Everything armed and due, oldest first.
     *
     * A NULL `next_due_at` is never due, which is why the query names the column rather than relying
     * on a comparison. In SQL, `NULL <= x` is unknown and the row is excluded, which is the behaviour
     * wanted here and is far too easy to get wrong by accident to leave implicit.
     */
    @Query(
        """
        SELECT * FROM agent_schedules
        WHERE enabled = 1 AND next_due_at IS NOT NULL AND next_due_at <= :now
        ORDER BY next_due_at ASC
        """
    )
    suspend fun due(now: Long): List<AgentScheduleEntity>

    @Query(
        """
        SELECT * FROM agent_schedules
        WHERE enabled = 1 AND next_due_at IS NOT NULL AND next_due_at <= :now
        ORDER BY next_due_at ASC
        """
    )
    fun dueFlow(now: Long): Flow<List<AgentScheduleEntity>>

    /**
     * Advance a schedule that has just started a run.
     *
     * CONDITIONAL ON THE DUE TIME THIS CALLER SAW. If another runner has already advanced it, this
     * changes nothing and returns zero, which is the caller's cue that the window was taken. Without
     * that condition two starts of the same window are possible and nothing in the code would
     * notice.
     */
    @Query(
        """
        UPDATE agent_schedules
        SET next_due_at = :nextDueAt, last_due_at = :ranAt, last_started_at = :ranAt,
            last_run_id = :runId, last_failure = NULL, updated_at = :ranAt
        WHERE schedule_id = :scheduleId AND next_due_at = :expectedDueAt
        """
    )
    suspend fun advance(
        scheduleId: String,
        expectedDueAt: Long,
        nextDueAt: Long?,
        runId: String,
        ranAt: Long,
    ): Int

    /**
     * Record a window that passed without a run.
     *
     * SEPARATE FROM [advance] BECAUSE IT IS A DIFFERENT OUTCOME. A window that could not run — no
     * model connected, the agent switched off, the device asleep — is not a start, and folding it
     * into advance would clear the failure and claim a run that never happened.
     */
    @Query(
        """
        UPDATE agent_schedules
        SET next_due_at = :nextDueAt, last_due_at = :dueAt, missed_count = missed_count + 1,
            last_failure = :reason, updated_at = :now
        WHERE schedule_id = :scheduleId AND next_due_at = :expectedDueAt
        """
    )
    suspend fun miss(
        scheduleId: String,
        expectedDueAt: Long,
        nextDueAt: Long?,
        dueAt: Long,
        reason: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE agent_schedules SET enabled = :enabled, next_due_at = :nextDueAt, updated_at = :now " +
            "WHERE schedule_id = :scheduleId"
    )
    suspend fun setEnabled(scheduleId: String, enabled: Boolean, nextDueAt: Long?, now: Long)

    /**
     * Attach the conversation this schedule's runs will belong to.
     *
     * WRITTEN ONCE, THE FIRST TIME THE SCHEDULE RUNS. A conditional update rather than a plain one so
     * two runners racing on the first window cannot each create a thread and leave one orphaned with
     * a run in it.
     */
    @Query(
        "UPDATE agent_schedules SET conversation_id = :conversationId, updated_at = :now " +
            "WHERE schedule_id = :scheduleId AND conversation_id IS NULL"
    )
    suspend fun attachConversation(scheduleId: String, conversationId: String, now: Long): Int

    @Query("DELETE FROM agent_schedules WHERE schedule_id = :scheduleId")
    suspend fun delete(scheduleId: String)
}
