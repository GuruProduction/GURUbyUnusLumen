// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.AutomationRunStatus
import com.unuslumen.app.domain.model.AutomationRunTrigger
import com.unuslumen.app.domain.model.AutomationStepTrace
import com.unuslumen.app.domain.model.GuruAutomationRun
import kotlinx.coroutines.flow.Flow

/**
 * Repository for automation run traces — the Observatory's truth floor.
 *
 * A run's lifecycle: [startRun] writes a running row, [traceStep] appends
 * steps as they execute, [completeRun] finalises status and duration. The
 * row is rewritten in place so the Flow re-emits live for anyone watching.
 * Every completion pass enforces retention, doctrine: the newest
 * AutomationRunRetention.RUNS_PER_AUTOMATION runs survive per automation,
 * all else prunes.
 */
interface AutomationRunRepository {

    suspend fun startRun(
        automationId: String,
        automationName: String,
        trigger: AutomationRunTrigger
    ): String

    suspend fun traceStep(runId: String, step: AutomationStepTrace)

    suspend fun completeRun(runId: String, status: AutomationRunStatus, durationMs: Long)

    suspend fun getRun(runId: String): GuruAutomationRun?

    suspend fun getRunsForAutomation(automationId: String, limit: Int = 50): List<GuruAutomationRun>

    fun getRunsForAutomationFlow(automationId: String, limit: Int = 50): Flow<List<GuruAutomationRun>>

    suspend fun getRecentRuns(limit: Int = 100): List<GuruAutomationRun>

    fun getRecentRunsFlow(limit: Int = 100): Flow<List<GuruAutomationRun>>

    /**
     * Sweep any run stuck in RUNNING past its expected lifetime (crashed
     * process, killed app mid-run) to NEEDS_ATTENTION so the honest ledger
     * stays honest. Called on app start.
     */
    suspend fun reconcileStaleRuns(olderThanMs: Long)

    suspend fun pruneForAutomation(automationId: String)

    suspend fun deleteRunsForAutomation(automationId: String)
}