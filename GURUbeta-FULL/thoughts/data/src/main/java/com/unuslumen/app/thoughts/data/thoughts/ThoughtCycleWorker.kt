// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.thoughts.data.thoughts

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unuslumen.app.thoughts.domain.model.ScheduledThoughtConfig
import com.unuslumen.app.thoughts.domain.model.ThresholdThoughtConfig
import com.unuslumen.app.thoughts.domain.model.ThoughtOutputType
import com.unuslumen.app.thoughts.domain.model.ThoughtTriggerType
import com.unuslumen.app.thoughts.domain.repository.ThoughtCycleRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.serialization.json.Json

/**
 * ThoughtCycleWorker — evaluates every enabled thought cycle's trigger and
 * executes the ones that are due.
 *
 * Runs on a fixed periodic WorkManager cadence (ThoughtCycleDispatcherWorker
 * owns the scheduling, every 15 minutes, WorkManager's minimum). For each
 * enabled cycle:
 *
 *  - SCHEDULED: runs when (lastRunAt ?: never) + intervalMs has elapsed.
 *    initialDelayMs shifts only the FIRST run after creation, which is why
 *    the due test uses createdAt as the base for the pre-first-run gate.
 *  - EVENT: is dispatched by the real-time event bridge (EventBridge), not
 *    here; this worker skips EVENT cycles entirely.
 *  - THRESHOLD: reads the configured metric live via DeviceMetrics.DataSource,
 *    applies the configured operator against the value, and respects the
 *    configured cooldown since the cycle's lastRunAt.
 *
 * Output routing (MEMORY facts, PROPOSAL records, ACTION counting) lives in
 * ThoughtCycleRepositoryImpl.executeCycle, so the worker only decides WHEN a
 * cycle runs, never WHAT a run produces. Every cycle is error-isolated: one
 * failing cycle never stops the remaining ones from being evaluated.
 */
class ThoughtCycleWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val thoughtCycleRepository: ThoughtCycleRepository by inject()
    private val metrics: DeviceMetrics.DataSource by lazy {
        DeviceMetrics.InAppMetrics(
            context = applicationContext,
            jobDao = com.unuslumen.app.thoughts.data.thoughts.ThoughtCycleProvider.jobDao(),
            insightDao = com.unuslumen.app.thoughts.data.thoughts.ThoughtCycleProvider.insightDao(),
            memoryRepository = com.unuslumen.app.thoughts.data.thoughts.ThoughtCycleProvider.memoryRepository()
        )
    }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        return try {
            val cycles = thoughtCycleRepository.getEnabledCycles()
            Log.d(TAG, "Trigger evaluation: ${cycles.size} enabled cycle(s)")
            if (cycles.isEmpty()) return Result.success()

            val now = System.currentTimeMillis()
            var executed = 0
            var failed = 0

            for (cycle in cycles) {
                if (cycle.triggerType == ThoughtTriggerType.EVENT) continue
                try {
                    val due = isDue(cycle, now)
                    if (!due) continue
                    Log.d(TAG, "Cycle '${cycle.name}' due — executing (${cycle.triggerType.name})")
                    val result = thoughtCycleRepository.executeCycle(cycle.id)
                    if (result.success) {
                        executed++
                    } else {
                        failed++
                        Log.w(TAG, "Cycle '${cycle.name}' failed: ${result.error}")
                    }
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "Cycle '${cycle.name}' evaluation failed: ${e.message}")
                }
            }

            Log.d(TAG, "Trigger sweep complete: $executed executed, $failed failed")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed (attempt $runAttemptCount): ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * The due decision, in plain terms. SCHEDULED compares elapsed time
     * against the configured interval. THRESHOLD evaluates metric/operator/
     * value plus cooldown. Anything else is skipped upstream.
     */
    private suspend fun isDue(cycle: com.unuslumen.app.thoughts.domain.model.GuruThoughtCycle, now: Long): Boolean {
        return when (cycle.triggerType) {
            ThoughtTriggerType.SCHEDULED -> {
                val config = try {
                    json.decodeFromString<ScheduledThoughtConfig>(cycle.triggerConfig)
                } catch (e: Exception) {
                    Log.w(TAG, "Cycle '${cycle.name}' bad scheduled config: ${e.message}")
                    return false
                }
                if (config.intervalMs <= 0) {
                    Log.w(TAG, "Cycle '${cycle.name}' scheduled intervalMs must be positive")
                    return false
                }
                val anchor = cycle.lastRunAt ?: (cycle.createdAt + config.initialDelayMs)
                now >= anchor + config.intervalMs
            }
            ThoughtTriggerType.THRESHOLD -> {
                val config = try {
                    json.decodeFromString<ThresholdThoughtConfig>(cycle.triggerConfig)
                } catch (e: Exception) {
                    Log.w(TAG, "Cycle '${cycle.name}' bad threshold config: ${e.message}")
                    return false
                }
                val current = metrics.read(config.metric)
                val crossed = when (config.operator.trim().lowercase()) {
                    "gt" -> current > config.value
                    "lt" -> current < config.value
                    "gte" -> current >= config.value
                    "lte" -> current <= config.value
                    "eq" -> current == config.value
                    else -> {
                        Log.w(TAG, "Cycle '${cycle.name}' unknown operator '${config.operator}'")
                        false
                    }
                }
                if (!crossed) return false
                val cooldownUntil = (cycle.lastRunAt ?: 0L) + config.cooldownMs
                if (now < cooldownUntil) {
                    Log.d(
                        TAG,
                        "Cycle '${cycle.name}' threshold crossed but in cooldown (${cooldownUntil - now}ms left)"
                    )
                    false
                } else true
            }
            ThoughtTriggerType.EVENT -> false
        }
    }

    companion object {
        const val TAG = "guru_thoughts"
        const val WORK_NAME = "thought_cycle_dispatch"
    }
}

/**
 * A tiny dependency locator: WorkManager workers are built by the Android OS
 * with no constructor injection, so the app's own pattern (KoinComponent +
 * inject) is used instead. ThoughtCycleProvider centralises those lookups in
 * one place rather than scattering org.koin calls through the worker logic.
 */
object ThoughtCycleProvider {
    @Suppress("unused")
    private fun unused() {}

    fun jobDao(): com.unuslumen.app.database.dao.GuruJobDao =
        org.koin.java.KoinJavaComponent.getKoin()
            .get<com.unuslumen.app.database.dao.GuruJobDao>()

    fun insightDao(): com.unuslumen.app.database.dao.GuruInsightDao =
        org.koin.java.KoinJavaComponent.getKoin()
            .get<com.unuslumen.app.database.dao.GuruInsightDao>()

    fun memoryRepository(): com.unuslumen.app.domain.memory.MemoryRepository =
        org.koin.java.KoinJavaComponent.getKoin()
            .get<com.unuslumen.app.domain.memory.MemoryRepository>()
}