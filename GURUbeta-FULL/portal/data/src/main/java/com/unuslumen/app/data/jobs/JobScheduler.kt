package com.unuslumen.app.data.jobs

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unuslumen.app.domain.model.CronConfig
import com.unuslumen.app.domain.model.DailyConfig
import com.unuslumen.app.domain.model.GuruJob
import com.unuslumen.app.domain.model.IntervalConfig
import com.unuslumen.app.domain.model.MonthlyConfig
import com.unuslumen.app.domain.model.ScheduleType
import com.unuslumen.app.domain.model.WeeklyConfig
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.concurrent.TimeUnit

object JobScheduler {
    private const val TAG = "guru_jobs"
    private val json = Json { ignoreUnknownKeys = true }

    private const val MIN_PERIODIC_INTERVAL_MS = 15L * 60 * 1000

    fun scheduleJob(context: Context, job: GuruJob) {
        val workName = JobExecutionWorker.workName(job.id)
        val workManager = WorkManager.getInstance(context)

        try {
            scheduleJobInternal(context, job, workName, workManager)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule job '${job.name}': ${e.message}", e)
        }
    }

    private fun scheduleJobInternal(context: Context, job: GuruJob, workName: String, workManager: WorkManager) {
        when (job.scheduleType) {
            ScheduleType.ONE_TIME -> {
                val now = System.currentTimeMillis()
                val nextRun = job.nextRunAt
                val delay = if (nextRun != null && nextRun > now) nextRun - now else 0L
                val request = OneTimeWorkRequestBuilder<JobExecutionWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(androidx.work.workDataOf(JobExecutionWorker.KEY_JOB_ID to job.id))
                    .build()
                workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
                Log.d(TAG, "Scheduled ONE_TIME job ${job.name} with delay ${delay}ms")
            }

            ScheduleType.INTERVAL -> {
                val config = json.decodeFromString<IntervalConfig>(job.scheduleConfig)
                if (config.intervalMs >= MIN_PERIODIC_INTERVAL_MS) {
                    val intervalMinutes = config.intervalMs / (60 * 1000)
                    val nextRun = job.nextRunAt
                    val initialDelay = if (nextRun != null) {
                        maxOf(0L, nextRun - System.currentTimeMillis())
                    } else {
                        config.initialDelayMs
                    }
                    val request = PeriodicWorkRequestBuilder<JobExecutionWorker>(intervalMinutes, TimeUnit.MINUTES)
                        .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(JobExecutionWorker.KEY_JOB_ID to job.id))
                        .build()
                    workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
                    Log.d(TAG, "Scheduled INTERVAL job ${job.name} every ${intervalMinutes}min, initial delay ${initialDelay}ms")
                } else {
                    val initialDelay = config.initialDelayMs
                    val request = OneTimeWorkRequestBuilder<JobExecutionWorker>()
                        .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(JobExecutionWorker.KEY_JOB_ID to job.id))
                        .build()
                    workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
                    Log.d(TAG, "Scheduled INTERVAL job ${job.name} with chained OneTimeWork, delay ${initialDelay}ms, interval ${config.intervalMs}ms")
                }
            }

            ScheduleType.DAILY -> {
                val config = json.decodeFromString<DailyConfig>(job.scheduleConfig)
                val initialDelay = calculateDailyDelay(config.hour, config.minute)
                val request = PeriodicWorkRequestBuilder<JobExecutionWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(JobExecutionWorker.KEY_JOB_ID to job.id))
                    .build()
                workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
                Log.d(TAG, "Scheduled DAILY job ${job.name} at ${config.hour}:${config.minute}, delay ${initialDelay}ms")
            }

            ScheduleType.WEEKLY -> {
                val config = json.decodeFromString<WeeklyConfig>(job.scheduleConfig)
                val initialDelay = calculateWeeklyDelay(config.daysOfWeek, config.hour, config.minute)
                val request = PeriodicWorkRequestBuilder<JobExecutionWorker>(7, TimeUnit.DAYS)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(JobExecutionWorker.KEY_JOB_ID to job.id))
                    .build()
                workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
                Log.d(TAG, "Scheduled WEEKLY job ${job.name} on days ${config.daysOfWeek} at ${config.hour}:${config.minute}, delay ${initialDelay}ms")
            }

            ScheduleType.MONTHLY -> {
                val config = json.decodeFromString<MonthlyConfig>(job.scheduleConfig)
                val initialDelay = calculateMonthlyDelay(config)
                val request = OneTimeWorkRequestBuilder<JobExecutionWorker>()
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(JobExecutionWorker.KEY_JOB_ID to job.id))
                    .build()
                workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
                Log.d(TAG, "Scheduled MONTHLY job ${job.name} on day ${config.dayOfMonth} at ${config.hour}:${config.minute}, delay ${initialDelay}ms")
            }

            ScheduleType.CRON -> {
                val config = json.decodeFromString<CronConfig>(job.scheduleConfig)
                val initialDelay = calculateCronNextDelay(config.expression)
                val request = OneTimeWorkRequestBuilder<JobExecutionWorker>()
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(JobExecutionWorker.KEY_JOB_ID to job.id))
                    .build()
                workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
                Log.d(TAG, "Scheduled CRON job ${job.name} with expression '${config.expression}', delay ${initialDelay}ms")
            }
        }
    }

    fun cancelJob(context: Context, jobId: String) {
        val workName = JobExecutionWorker.workName(jobId)
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        Log.d(TAG, "Cancelled WorkManager work for job $jobId")
    }

    fun rescheduleJob(context: Context, job: GuruJob) {
        cancelJob(context, job.id)
        scheduleJob(context, job)
    }

    private fun calculateDailyDelay(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val now = System.currentTimeMillis()
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis - now
    }

    private fun calculateWeeklyDelay(daysOfWeek: List<Int>, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val calDaysOfWeek = daysOfWeek.map { d ->
            when (d) {
                1 -> Calendar.MONDAY
                2 -> Calendar.TUESDAY
                3 -> Calendar.WEDNESDAY
                4 -> Calendar.THURSDAY
                5 -> Calendar.FRIDAY
                6 -> Calendar.SATURDAY
                7 -> Calendar.SUNDAY
                else -> Calendar.MONDAY
            }
        }

        val now = System.currentTimeMillis()
        for (i in 0..7) {
            val checkCal = cal.clone() as Calendar
            checkCal.add(Calendar.DAY_OF_MONTH, i)
            if (checkCal.get(Calendar.DAY_OF_WEEK) in calDaysOfWeek && checkCal.timeInMillis > now) {
                return checkCal.timeInMillis - now
            }
        }
        return 24 * 60 * 60 * 1000L
    }

    private fun calculateMonthlyDelay(config: MonthlyConfig): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, minOf(config.dayOfMonth, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        cal.set(Calendar.HOUR_OF_DAY, config.hour)
        cal.set(Calendar.MINUTE, config.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
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