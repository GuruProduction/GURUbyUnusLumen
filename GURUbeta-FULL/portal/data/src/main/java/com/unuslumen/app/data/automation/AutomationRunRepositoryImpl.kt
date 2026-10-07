// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.automation

import com.unuslumen.app.database.dao.GuruAutomationRunDao
import com.unuslumen.app.database.entity.GuruAutomationRunEntity
import com.unuslumen.app.domain.model.AutomationRunStatus
import com.unuslumen.app.domain.model.AutomationRunRetention
import com.unuslumen.app.domain.model.AutomationRunTrigger
import com.unuslumen.app.domain.model.AutomationStepTrace
import com.unuslumen.app.domain.model.GuruAutomationRun
import com.unuslumen.app.domain.repository.AutomationRunRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [AutomationRunRepository::class])
class AutomationRunRepositoryImpl(
    private val runDao: GuruAutomationRunDao
) : AutomationRunRepository {

    private val json = Json { ignoreUnknownKeys = true }

    // In-memory trace accumulation per run — the entity row is rewritten on
    // every step so the Flow re-emits live for observers. Single-writer per
    // run by construction (executeAutomation runs steps sequentially).
    private val activeTraces = mutableMapOf<String, MutableList<AutomationStepTrace>>()
    private val runMeta = mutableMapOf<String, Pair<String, AutomationRunTrigger>>()

    private fun encodeSteps(steps: List<AutomationStepTrace>): String =
        json.encodeToString(ListSerializer(AutomationStepTrace.serializer()), steps)

    private fun decodeSteps(raw: String): List<AutomationStepTrace> = try {
        json.decodeFromString(ListSerializer(AutomationStepTrace.serializer()), raw)
    } catch (e: Exception) {
        emptyList()
    }

    private fun entityToDomain(entity: GuruAutomationRunEntity): GuruAutomationRun = GuruAutomationRun(
        id = entity.id,
        automationId = entity.automationId,
        automationName = entity.automationName,
        trigger = AutomationRunTrigger.valueOf(entity.trigger),
        startedAt = entity.startedAt,
        durationMs = entity.durationMs,
        status = AutomationRunStatus.valueOf(entity.status),
        steps = decodeSteps(entity.stepsTraceJson),
        createdAt = entity.createdAt
    )

    override suspend fun startRun(
        automationId: String,
        automationName: String,
        trigger: AutomationRunTrigger
    ): String {
        val runId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        activeTraces[runId] = mutableListOf()
        runMeta[runId] = automationId to trigger
        withContext(Dispatchers.IO) {
            runDao.insertRun(
                GuruAutomationRunEntity(
                    id = runId,
                    automationId = automationId,
                    automationName = automationName,
                    trigger = trigger.name,
                    startedAt = now,
                    durationMs = 0,
                    status = AutomationRunStatus.RUNNING.name,
                    stepsTraceJson = "[]",
                    createdAt = now
                )
            )
        }
        return runId
    }

    override suspend fun traceStep(runId: String, step: AutomationStepTrace) {
        val traces = activeTraces[runId] ?: return
        val meta = runMeta[runId] ?: return
        synchronized(traces) { traces.add(step) }
        withContext(Dispatchers.IO) {
            val existing = runDao.getRun(runId) ?: return@withContext
            runDao.updateRun(
                existing.copy(
                    stepsTraceJson = encodeSteps(traces.toList()),
                    durationMs = System.currentTimeMillis() - existing.startedAt
                )
            )
        }
    }

    override suspend fun completeRun(runId: String, status: AutomationRunStatus, durationMs: Long) {
        val traces = activeTraces.remove(runId) ?: mutableListOf()
        runMeta.remove(runId)
        withContext(Dispatchers.IO) {
            val existing = runDao.getRun(runId) ?: return@withContext
            runDao.updateRun(
                existing.copy(
                    status = status.name,
                    durationMs = durationMs,
                    stepsTraceJson = if (traces.isNotEmpty()) encodeSteps(traces) else existing.stepsTraceJson
                )
            )
            // Retention, doctrine: runs roll. Newest survive, history stays queryable.
            runDao.pruneRuns(existing.automationId, AutomationRunRetention.RUNS_PER_AUTOMATION)
        }
    }

    override suspend fun getRun(runId: String): GuruAutomationRun? = withContext(Dispatchers.IO) {
        runDao.getRun(runId)?.let { entityToDomain(it) }
    }

    override suspend fun getRunsForAutomation(automationId: String, limit: Int): List<GuruAutomationRun> =
        withContext(Dispatchers.IO) {
            runDao.getRunsForAutomation(automationId, limit).map { entityToDomain(it) }
        }

    override fun getRunsForAutomationFlow(automationId: String, limit: Int): Flow<List<GuruAutomationRun>> =
        runDao.getRunsForAutomationFlow(automationId, limit).map { runs -> runs.map { entityToDomain(it) } }

    override suspend fun getRecentRuns(limit: Int): List<GuruAutomationRun> = withContext(Dispatchers.IO) {
        runDao.getRecentRuns(limit).map { entityToDomain(it) }
    }

    override fun getRecentRunsFlow(limit: Int): Flow<List<GuruAutomationRun>> =
        runDao.getRecentRunsFlow(limit).map { runs -> runs.map { entityToDomain(it) } }

    override suspend fun reconcileStaleRuns(olderThanMs: Long) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val stale = runDao.getStaleRunningRuns(cutoff)
        for (run in stale) {
            runDao.updateRun(
                run.copy(status = AutomationRunStatus.NEEDS_ATTENTION.name)
            )
        }
    }

    override suspend fun pruneForAutomation(automationId: String) = withContext(Dispatchers.IO) {
        runDao.pruneRuns(automationId, AutomationRunRetention.RUNS_PER_AUTOMATION)
    }

    override suspend fun deleteRunsForAutomation(automationId: String) = withContext(Dispatchers.IO) {
        runDao.deleteRunsForAutomation(automationId)
    }
}