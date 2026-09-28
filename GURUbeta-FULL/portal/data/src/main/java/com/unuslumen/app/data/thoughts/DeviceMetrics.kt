package com.unuslumen.app.data.thoughts

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter

/**
 * DeviceMetrics — real on-device metric source for THRESHOLD thought cycles.
 *
 * Metrics that need DAOs or repositories (conversation_count, memory_fact_count,
 * insight_count, total_facts, failed_jobs, total_jobs) are served by
 * [DeviceMetrics.InAppMetrics] below, which owns the real app data sources.
 *
 * System metrics this object measures directly at call time:
 *  - battery_level / battery_pct : battery percentage 0-100
 *  - storage_free_mb            : free megabytes on internal data volume
 *  - foreground_apps            : packages touched in the last 24h via
 *                                 UsageStatsManager (needs the special
 *                                 usage-access permission; 0 is a valid,
 *                                 honest evaluation when it is missing)
 *
 * Unknown metric names evaluate to 0.0 with a logged warning, so a THRESHOLD
 * cycle whose metric never exists keeps evaluating and never crashes the
 * background worker. Cooldown in ThresholdThoughtConfig stops a permanent 0
 * from firing without end.
 */
object DeviceMetrics {

    /** Every metric name THRESHOLD cycles may legitimately reference. */
    val supportedMetrics: List<String> = listOf(
        "unread_notifications",
        "battery_level",
        "battery_pct",
        "conversation_count",
        "memory_fact_count",
        "total_facts",
        "insight_count",
        "failed_jobs",
        "total_jobs",
        "foreground_apps",
        "storage_free_mb"
    )

    /**
     * System metric read. Never throws: any failure logs and returns 0.0
     * because a metric evaluation must never crash the background worker.
     * DB-backed names resolve to 0 here; the real values come from
     * InAppMetrics, which has the app's own data sources.
     */
    fun read(context: Context, metric: String): Double = try {
        when (metric.trim().lowercase().replace(' ', '_')) {
            "battery_level", "battery_pct" -> batteryPercent(context).toDouble()
            "storage_free_mb" -> storageFreeMb(context)
            "foreground_apps" -> foregroundAppCount(context)
            else -> {
                android.util.Log.d(
                    "guru_thoughts",
                    "metric '$metric' is app-internal (InAppMetrics) or unknown; system source reports 0"
                )
                0.0
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("guru_thoughts", "metric '$metric' read failed: ${e.message}")
        0.0
    }

    /**
     * Battery percentage from the sticky ACTION_BATTERY_CHANGED intent.
     * registerReceiver(null, ...) means no receiver leaks and no manifest
     * permission is needed; a missing broadcast evaluates to 0.
     */
    fun batteryPercent(context: Context): Int = try {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100 / scale else 0
        } else 0
    } catch (e: Exception) {
        android.util.Log.w("guru_thoughts", "batteryPercent failed: ${e.message}")
        0
    }

    /** Free internal storage in megabytes. */
    private fun storageFreeMb(context: Context): Double = try {
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
        stat.availableBytes / (1024.0 * 1024.0)
    } catch (e: Exception) {
        0.0
    }

    /**
     * Packages with usage in the last 24h. Without the usage-access special
     * permission the call throws or returns an empty map; both evaluate to 0,
     * a valid reading, so threshold logic proceeds against the real value.
     */
    private fun foregroundAppCount(context: Context): Double = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0.0
        val now = System.currentTimeMillis()
        val since = now - 24L * 60L * 60L * 1000L
        usm.queryAndAggregateUsageStats(since, now)
            .values.count { it.totalTimeInForeground > 0 }.toDouble()
    } catch (e: Exception) {
        0.0
    }

    /**
     * The data source behind THRESHOLD evaluation. ThoughtCycleRepositoryImpl
     * supplies the real implementation wired to its own DAOs and memory
     * repository, keeping the metric layer free of data-layer dependencies.
     */
    interface DataSource {
        suspend fun read(metric: String): Double
    }

    /**
     * Database-backed metric source for the in-app counter metrics. Falls
     * through to [DeviceMetrics.read] for system metrics so one source object
     * serves every metric name the configs may name.
     */
    class InAppMetrics(
        private val context: Context,
        private val jobDao: com.unuslumen.app.database.dao.GuruJobDao,
        private val insightDao: com.unuslumen.app.database.dao.GuruInsightDao,
        private val memoryRepository: com.unuslumen.app.domain.memory.MemoryRepository
    ) : DataSource {
        override suspend fun read(metric: String): Double = try {
            when (metric.trim().lowercase().replace(' ', '_')) {
                "conversation_count" ->
                    memoryRepository.getAllConversations().size.toDouble()
                "memory_fact_count", "total_facts" ->
                    memoryRepository.getTotalFacts().toDouble()
                "insight_count" ->
                    insightDao.getUnacknowledgedInsights().size.toDouble()
                "unread_notifications" ->
                    insightDao.getUnacknowledgedInsights().size.toDouble()
                "failed_jobs" ->
                    jobDao.getAllJobs().count { it.failureCount > 0 }.toDouble()
                "total_jobs" ->
                    jobDao.getAllJobs().size.toDouble()
                else -> DeviceMetrics.read(context, metric)
            }
        } catch (e: Exception) {
            android.util.Log.w("guru_thoughts", "in-app metric '$metric' failed: ${e.message}")
            0.0
        }
    }
}