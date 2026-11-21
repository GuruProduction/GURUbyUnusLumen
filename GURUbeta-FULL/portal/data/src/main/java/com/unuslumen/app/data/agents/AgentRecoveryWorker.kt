// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The recovery scan, running where Android will actually let it.
 *
 * WHY WORKMANAGER AND NOT THE SERVICE. WorkManager survives process death by design, which
 * is exactly what a scan looking for dead processes needs. It does NOT own the live
 * cognitive loop: a `CoroutineWorker` has its own execution budget and a long model
 * conversation does not fit inside one, so the loop stays in the foreground service and the
 * scan lives here.
 *
 * WHAT IT DOES is one thing: ask the coordinator which runs look dead, and let it write
 * INTERRUPTED for the ones that are. It does not resume anything. Resuming is a decision with
 * consequences, and a background scan is the wrong place to make it: it can happen on the
 * next launch, in the open, where a person can see it.
 */
class AgentRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val coordinator: AgentRecoveryCoordinator by inject()

    /**
     * The schedule sweep, which rides this worker rather than owning a second one.
     *
     * WHY THE SAME WAKE-UP. A schedule is due at a time, not on a cadence of its own, and WorkManager's
     * minimum periodic interval is fifteen minutes, which is exactly what this worker already asks
     * for. A second worker would double the wake-ups on every phone to do the same amount of work at
     * the same frequency. One pass, two questions.
     *
     * THE TWO JOBS ARE INDEPENDENT IN FAILURE. A sweep that cannot read the schedule table must not
     * stop a dead run being marked interrupted, and a recovery scan that throws must not leave due
     * schedules unrun, so each is caught on its own.
     */
    private val schedules: AgentScheduleService by inject()

    override suspend fun doWork(): Result {
        runSchedules()
        return try {
            val report = coordinator.scan(AgentRecoveryCoordinator.newOwnerId())
            android.util.Log.d(
                "guru_agents",
                "Recovery scan: ${report.scanned} suspect(s), ${report.interrupted} marked interrupted, " +
                    "${report.skippedAlive} still alive."
            )
            Result.success()
        } catch (e: Exception) {
            // RETRY, because a scan that could not read the database has not learned that
            // there are no dead runs; it has learned nothing. Success would be a lie that
            // leaves dead runs looking alive for another cycle.
            android.util.Log.w("guru_agents", "Recovery scan failed: ${e.message}")
            Result.retry()
        }
    }

    /**
     * Run everything due, and report what happened.
     *
     * A FAILURE HERE DOES NOT FAIL THE WORKER. A schedule that could not start records its own reason
     * on its own row, so the next pass and any surface can see it. Failing the whole worker would
     * retry the recovery scan too, which had nothing to do with it.
     */
    private suspend fun runSchedules() {
        runCatching { schedules.sweep() }
            .onSuccess { report ->
                if (report.due > 0) {
                    android.util.Log.d(
                        "guru_agents",
                        "Schedule sweep: ${report.due} due, ${report.started} started, " +
                            "${report.refused.size} refused.",
                    )
                }
            }
            .onFailure {
                android.util.Log.w("guru_agents", "Schedule sweep failed: ${it.message}")
            }
    }

    companion object {
        const val UNIQUE_NAME = "guru_agent_recovery_scan"

        /** Every fifteen minutes, which is WorkManager's minimum periodic interval. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AgentRecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        // No network requirement: the scan reads the local database and writes
                        // to it. Requiring a network would delay recovery for no reason.
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP, so a schedule that already exists is not reset on every launch. A
                // periodic worker that restarts its window each start never actually runs.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Run one scan now, for the app-start path. */
        fun runNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<AgentRecoveryWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
