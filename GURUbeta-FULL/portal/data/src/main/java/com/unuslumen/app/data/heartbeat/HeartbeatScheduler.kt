package com.unuslumen.app.data.heartbeat

import android.content.Context
import android.util.Log
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the heartbeat: every 30 minutes, founder-specified, no exceptions.
 *
 * A single fixed 30-minute periodic WorkManager job. No preference, no policy
 * switches, no constraints: the framework wakes GURU every 30 minutes of the
 * day and the bundled prompt (assets/heartbeat/HEARTBEAT_PROMPT.md) decides
 * what a wake means. ExistingPeriodicWorkPolicy.UPDATE keeps re-enqueueing
 * idempotent across boots.
 *
 * Doze deferral by the OS is platform behaviour outside the app's control and
 * is documented in HeartbeatWorker; the job itself is scheduled unconditionally.
 */
object HeartbeatScheduler {

    private const val TAG = "guru_heartbeat"
    private const val WORK_NAME = "guru_heartbeat"

    /** Founder-specified cadence: every 30 minutes. */
    private const val INTERVAL_MINUTES = 30L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .build()

        try {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            Log.d(TAG, "Heartbeat scheduled every ${INTERVAL_MINUTES}min, no gates")
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat schedule failed: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Heartbeat cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat cancel failed: ${e.message}")
        }
    }
}