package com.unuslumen.app.data.jobs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unuslumen.app.domain.model.ScheduleType
import com.unuslumen.app.domain.repository.JobRepository
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar
import java.util.concurrent.TimeUnit

class JobExecutionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val jobRepository: JobRepository by inject()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "guru_jobs"
        const val KEY_JOB_ID = "jobId"
        private const val WORK_NAME_PREFIX = "job_"

        fun workName(jobId: String) = "$WORK_NAME_PREFIX$jobId"
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.success()

        return try {
            val job = jobRepository.getJob(jobId)
            if (job == null) {
                Log.d(TAG, "Job $jobId not found, not rescheduling")
                return Result.success()
            }
            if (!job.enabled) {
                Log.d(TAG, "Job $jobId is disabled, not rescheduling")
                return Result.success()
            }

            Log.d(TAG, "Executing job: ${job.name} ($jobId)")
            val result = jobRepository.executeJob(jobId)

            if (result.success) {
                Log.d(TAG, "Job ${job.name} executed successfully in ${result.executionTimeMs}ms")
            } else {
                Log.w(TAG, "Job ${job.name} failed: ${result.error}")
            }

            if (job.enabled) {
                rescheduleIfNeeded(job)
            }

            Result.success()
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Job $jobId not found or disabled, not retrying: ${e.message}")
            Result.success()
        } catch (e: IllegalStateException) {
            Log.d(TAG, "Job $jobId is disabled, not retrying: ${e.message}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Job $jobId execution error: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun rescheduleIfNeeded(job: com.unuslumen.app.domain.model.GuruJob) {
        when (job.scheduleType) {
            ScheduleType.INTERVAL -> {
                val config = json.decodeFromString<com.unuslumen.app.domain.model.IntervalConfig>(job.scheduleConfig)
                if (config.intervalMs < 15 * 60 * 1000L) {
                    val request = OneTimeWorkRequestBuilder<JobExecutionWorker>()
                        .setInitialDelay(config.intervalMs, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_JOB_ID to job.id))
                        .build()
                    WorkManager.getInstance(applicationContext)
                        .enqueueUniqueWork(workName(job.id), ExistingWorkPolicy.REPLACE, request)
                }
            }
            ScheduleType.MONTHLY -> {
                val config = json.decodeFromString<com.unuslumen.app.domain.model.MonthlyConfig>(job.scheduleConfig)
                val nextDelay = calculateMonthlyDelay(config)
                val request = OneTimeWorkRequestBuilder<JobExecutionWorker>()
                    .setInitialDelay(nextDelay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_JOB_ID to job.id))
                    .build()
                WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(workName(job.id), ExistingWorkPolicy.REPLACE, request)
            }
            ScheduleType.CRON -> {
                val config = json.decodeFromString<com.unuslumen.app.domain.model.CronConfig>(job.scheduleConfig)
                val nextDelay = calculateCronNextDelay(config.expression)
                if (nextDelay > 0) {
                    val request = OneTimeWorkRequestBuilder<JobExecutionWorker>()
                        .setInitialDelay(nextDelay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_JOB_ID to job.id))
                        .build()
                    WorkManager.getInstance(applicationContext)
                        .enqueueUniqueWork(workName(job.id), ExistingWorkPolicy.REPLACE, request)
                }
            }
            else -> {}
        }
    }

    private fun calculateMonthlyDelay(config: com.unuslumen.app.domain.model.MonthlyConfig): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, minOf(config.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        cal.set(Calendar.HOUR_OF_DAY, config.hour)
        cal.set(Calendar.MINUTE, config.minute)
        cal.set(Calendar.SECOND, 0)
        val now = System.currentTimeMillis()
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.MONTH, 1)
            cal.set(Calendar.DAY_OF_MONTH, minOf(config.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return cal.timeInMillis - now
    }

    private fun calculateCronNextDelay(expression: String): Long {
        val parts = expression.trim().split("\\s+".toRegex())
        if (parts.size < 5) return 60 * 60 * 1000L

        val minuteField = parts[0]
        val hourField = parts[1]
        val dayOfMonthField = parts[2]
        val monthField = parts[3]
        val dayOfWeekField = parts[4]

        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, 1)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val maxTime = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
        while (cal.timeInMillis < maxTime) {
            val minute = cal.get(Calendar.MINUTE)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val cronDow = if (dow == 1) 0 else dow - 1

            if (matchesField(minuteField, minute, 0, 59) &&
                matchesField(hourField, hour, 0, 23) &&
                matchesField(dayOfMonthField, dom, 1, 31) &&
                matchesField(monthField, month, 1, 12) &&
                matchesField(dayOfWeekField, cronDow, 0, 6)
            ) {
                return cal.timeInMillis - System.currentTimeMillis()
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return 60 * 60 * 1000L
    }

    private fun matchesField(field: String, value: Int, min: Int, max: Int): Boolean {
        if (field == "*") return true
        if (field.startsWith("*/")) {
            val step = field.substring(2).toIntOrNull() ?: 1
            return value % step == 0
        }
        if (field.contains(",")) {
            return field.split(",").any { matchesField(it.trim(), value, min, max) }
        }
        if (field.contains("-")) {
            val range = field.split("-")
            val start = range[0].toIntOrNull() ?: min
            val end = range[1].toIntOrNull() ?: max
            return value in start..end
        }
        return field.toIntOrNull() == value
    }
}