package com.unuslumen.app.data.brain

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * BrainScheduler — Schedules the background brain workers.
 *
 * DreamWorker runs once per day. No device idle requirement so it fires
 * within the first WorkManager cycle (typically 15 minutes).
 * DecayWorker runs every 12 hours.
 *
 * Call schedule() once on app startup. WorkManager keeps them scheduled
 * across app restarts.
 */
object BrainScheduler {

    private const val TAG = "guru_brain"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)

        // DreamWorker: runs once per day
        // No device idle constraint — we want it to fire on first cycle
        // so old facts get backfilled on app install/update
        val dreamConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val dreamRequest = PeriodicWorkRequestBuilder<DreamWorker>(1, TimeUnit.DAYS)
            .setConstraints(dreamConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DreamWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dreamRequest
        )

        // DecayWorker: runs every 12 hours
        val decayConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val decayRequest = PeriodicWorkRequestBuilder<DecayWorker>(12, TimeUnit.HOURS)
            .setConstraints(decayConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DecayWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            decayRequest
        )

        Log.d(TAG, "Brain workers scheduled: dream (1 day), decay (12 hours)")
    }
}
