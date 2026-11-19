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

    override suspend fun doWork(): Result {
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
