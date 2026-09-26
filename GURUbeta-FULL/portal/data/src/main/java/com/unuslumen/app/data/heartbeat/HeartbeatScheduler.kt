package com.unuslumen.app.data.heartbeat

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic heartbeat.
 *
 * Enqueued with ExistingPeriodicWorkPolicy.UPDATE — the same approach
 * JobCompressionWorker uses for minute-granularity periodic jobs — so interval
 * preference changes take effect on the next boot of the worker without
 * creating duplicate work chains. Minimum WorkManager periodic interval is
 * 15 minutes; anything lower is clamped.
 *
 * Constraints: network connected only. Deliberately NO idle / battery-low
 * constraints: an idle constraint would push beats deep into the night,
 * the opposite of what this feature is for. The quiet-hours gate inside
 * HeartbeatWorker handles "do not spend tokens while the human sleeps".
 *
 * Called once from GuruApplication.onCreate, the same boot point where every
 * other background scheduler in this app registers.
 */
object HeartbeatScheduler {

    private const val TAG = "guru_heartbeat"
    private const val WORK_NAME = "guru_heartbeat"

    /** WorkManager's hard minimum for periodic work. */
    private const val MIN_INTERVAL_MINUTES = 15L

    /** Default beat cadence when no preference is saved: every 30 minutes. */
    private const val DEFAULT_INTERVAL_MINUTES = 30L

    fun schedule(context: Context, getPreference: GetPreferenceUseCase) {
        val intervalMinutes = try {
            val raw = runBlocking {
                getPreference(
                    intPreferencesKey(PrefsConstants.HEARTBEAT_INTERVAL_MIN_KEY),
                    DEFAULT_INTERVAL_MINUTES.toInt()
                ).first()
            }
            raw.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES)
        } catch (e: Exception) {
            Log.w(TAG, "Interval pref read failed, using default ${DEFAULT_INTERVAL_MINUTES}min: ${e.message}")
            DEFAULT_INTERVAL_MINUTES
        }

        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        try {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            Log.d(TAG, "Heartbeat scheduled every ${intervalMinutes}min")
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