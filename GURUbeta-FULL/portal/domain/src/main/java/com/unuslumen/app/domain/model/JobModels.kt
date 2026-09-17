package com.unuslumen.app.domain.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Types of job schedules.
 */
enum class ScheduleType(val displayName: String, val description: String) {
    ONE_TIME("One Time", "Run once at a specific time"),
    INTERVAL("Interval", "Run repeatedly at a fixed interval"),
    DAILY("Daily", "Run once per day at a specific time"),
    WEEKLY("Weekly", "Run once per week on specific days"),
    MONTHLY("Monthly", "Run once per month on a specific day"),
    CRON("Cron", "Advanced scheduling using cron expressions")
}

/**
 * Configuration for interval-based jobs.
 */
@Serializable
data class IntervalConfig(
    val intervalMs: Long,        // Interval in milliseconds
    val initialDelayMs: Long = 0 // Initial delay before first run
)

/**
 * Configuration for daily jobs.
 */
@Serializable
data class DailyConfig(
    val hour: Int,              // Hour (0-23)
    val minute: Int = 0         // Minute (0-59)
)

/**
 * Configuration for weekly jobs.
 */
@Serializable
data class WeeklyConfig(
    val daysOfWeek: List<Int>,   // Days of week (1-7, where 1 is Monday)
    val hour: Int,               // Hour (0-23)
    val minute: Int = 0         // Minute (0-59)
)

/**
 * Configuration for monthly jobs.
 */
@Serializable
data class MonthlyConfig(
    val dayOfMonth: Int,         // Day of month (1-31)
    val hour: Int,               // Hour (0-23)
    val minute: Int = 0         // Minute (0-59)
)

/**
 * Configuration for cron-based jobs.
 */
@Serializable
data class CronConfig(
    val expression: String       // Cron expression (e.g., "0 7 * * *" for 7 AM daily)
)

/**
 * A scheduled job definition.
 */
@Serializable
data class GuruJob(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val scheduleType: ScheduleType,
    val scheduleConfig: String,  // JSON config
    val action: JobAction,
    val input: Map<String, @Contextual Any?>? = null,
    val enabled: Boolean,
    val createdAt: Long,
    val lastRunAt: Long?,
    val nextRunAt: Long?,
    val runCount: Int,
    val lastResult: String?,
    val failureCount: Int,
    val lastError: String?,
    val destinationTable: String? = null,
    val retentionWindowHours: Int? = null,
    val compressionEnabled: Boolean = false,
    val compressionCycleMs: Long? = null,
    val failureThreshold: Int? = null,
    val retryBackoffMs: Long? = null,
    val autoDisableMessage: String? = null,
    val historyRetentionHours: Int? = null
)

/**
 * Action to execute when job runs.
 */
@Serializable
data class JobAction(
    val type: String,           // "skill" or "tool"
    val target: String,         // Skill name or tool name
    val params: Map<String, String> = emptyMap()
)

/**
 * Request to create a new job.
 */
@Serializable
data class CreateJobRequest(
    val name: String,
    val displayName: String,
    val description: String,
    val scheduleType: ScheduleType,
    val scheduleConfig: String,  // JSON config
    val action: JobAction,
    val input: Map<String, @Contextual Any?>? = null,
    val destinationTable: String? = null,
    val retentionWindowHours: Int? = null,
    val compressionEnabled: Boolean = false,
    val compressionCycleMs: Long? = null,
    val failureThreshold: Int? = null,
    val retryBackoffMs: Long? = null,
    val autoDisableMessage: String? = null,
    val historyRetentionHours: Int? = null
)

/**
 * Result of job execution.
 */
@Serializable
data class JobExecutionResult(
    val jobId: String,
    val jobName: String,
    val startedAt: Long,
    val completedAt: Long,
    val success: Boolean,
    val result: String?,
    val error: String?,
    val executionTimeMs: Long
)

/**
 * Summary of jobs.
 */
@Serializable
data class JobsSummary(
    val totalJobs: Int,
    val enabledJobs: Int,
    val oneTimeJobs: Int,
    val intervalJobs: Int,
    val dailyJobs: Int,
    val weeklyJobs: Int,
    val monthlyJobs: Int,
    val cronJobs: Int,
    val totalRuns: Int,
    val failedRuns: Int,
    val nextRun: Long?
)