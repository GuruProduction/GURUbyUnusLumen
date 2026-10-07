// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.GuruAutomationRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruAutomationRunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: GuruAutomationRunEntity)

    /**
     * Live-run update pattern: the trace hook writes the row once at start
     * with status=running, then rewrites the same primary-keyed row as steps
     * execute and when it lands. Room's REPLACE conflict + primary key keeps
     * this simple and the Flow re-emits on every write.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateRun(run: GuruAutomationRunEntity)

    @Query("SELECT * FROM guru_automation_runs WHERE automationId = :automationId ORDER BY started_at DESC LIMIT :limit")
    suspend fun getRunsForAutomation(automationId: String, limit: Int): List<GuruAutomationRunEntity>

    @Query("SELECT * FROM guru_automation_runs WHERE automationId = :automationId ORDER BY started_at DESC LIMIT :limit")
    fun getRunsForAutomationFlow(automationId: String, limit: Int): Flow<List<GuruAutomationRunEntity>>

    @Query("SELECT * FROM guru_automation_runs ORDER BY started_at DESC LIMIT :limit")
    suspend fun getRecentRuns(limit: Int): List<GuruAutomationRunEntity>

    @Query("SELECT * FROM guru_automation_runs ORDER BY started_at DESC LIMIT :limit")
    fun getRecentRunsFlow(limit: Int): Flow<List<GuruAutomationRunEntity>>

    @Query("SELECT * FROM guru_automation_runs WHERE id = :id")
    suspend fun getRun(id: String): GuruAutomationRunEntity?

    @Query("SELECT * FROM guru_automation_runs WHERE status = 'running' AND started_at < :olderThan")
    suspend fun getStaleRunningRuns(olderThan: Long): List<GuruAutomationRunEntity>

    @Query("DELETE FROM guru_automation_runs WHERE automationId = :automationId")
    suspend fun deleteRunsForAutomation(automationId: String)

    /**
     * Retention, doctrine: deep history stays queryable but storage rolls.
     * Keeps the newest [keep] runs per automation, all else prunes.
     */
    @Query("DELETE FROM guru_automation_runs WHERE automationId = :automationId AND id NOT IN (SELECT id FROM guru_automation_runs WHERE automationId = :automationId ORDER BY started_at DESC LIMIT :keep)")
    suspend fun pruneRuns(automationId: String, keep: Int)

    @Query("SELECT COUNT(*) FROM guru_automation_runs WHERE automationId = :automationId")
    suspend fun getRunCount(automationId: String): Int

    /** Total runs across all automations — the time-given-back numerator lives here. */
    @Query("SELECT COUNT(*) FROM guru_automation_runs WHERE status != 'running'")
    suspend fun getTotalCompletedRunCount(): Int
}