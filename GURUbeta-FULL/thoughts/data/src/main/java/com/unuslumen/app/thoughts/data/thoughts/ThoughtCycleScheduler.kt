// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.thoughts.data.thoughts

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * ThoughtCycleScheduler — keeps the thought-cycle trigger engine running.
 *
 * ONE fixed periodic WorkManager job every 15 minutes (the WorkManager
 * minimum): ThoughtCycleWorker evaluates every enabled cycle's trigger and
 * runs the due ones. Per-cycle cadence lives in each cycle's triggerConfig
 * (ScheduledThoughtConfig.intervalMs / ThresholdThoughtConfig.cooldownMs),
 * evaluated inside the worker, so creating, editing or disabling a cycle
 * never requires rescheduling anything.
 *
 * ExistingPeriodicWorkPolicy.KEEP makes scheduling idempotent across every
 * boot: the job survives app restarts and reboots because WorkManager
 * persists it, and re-calling schedule() on startup keeps the old cadence
 * rather than resetting it.
 */
object ThoughtCycleScheduler {

    private const val TAG = "guru_thoughts"

    /** WorkManager minimum is 15 minutes; nothing is gained by trying lower. */
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<ThoughtCycleWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                ThoughtCycleWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Thought cycle dispatcher scheduled every ${INTERVAL_MINUTES}min")
        } catch (e: Exception) {
            Log.w(TAG, "Thought cycle scheduling failed: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context)
                .cancelUniqueWork(ThoughtCycleWorker.WORK_NAME)
            Log.d(TAG, "Thought cycle dispatcher cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "Thought cycle cancel failed: ${e.message}")
        }
    }
}