package com.unuslumen.app.data.jobs

import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.database.dao.GuruJobDao
import com.unuslumen.app.database.dao.JobExecutionHistoryDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.unuslumen.app.database.entity.GuruJobEntity
import com.unuslumen.app.database.entity.JobExecutionHistoryEntity
import com.unuslumen.app.domain.model.*
import com.unuslumen.app.domain.repository.JobRepository
import com.unuslumen.app.domain.repository.AutomationRepository
import com.unuslumen.app.domain.repository.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.core.annotation.Single
import java.util.Calendar
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [JobRepository::class])
class JobRepositoryImpl(
    private val jobDao: GuruJobDao,
    private val automationRepository: AutomationRepository,
    private val historyDao: JobExecutionHistoryDao
) : JobRepository, KoinComponent {

    private val toolRegistryHolder: ToolRegistryHolder by inject()
    private val toolRegistry get() = toolRegistryHolder.toolRegistry

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createJob(request: CreateJobRequest): GuruJob = withContext(Dispatchers.IO) {
        val nextRun = calculateNextRunForConfig(request.scheduleType, request.scheduleConfig)
        
        val job = GuruJobEntity(
            id = Uuid.random().toString(),
            name = request.name,
            displayName = request.displayName,
            description = request.description,
            scheduleType = request.scheduleType.name,
            scheduleConfig = request.scheduleConfig,
            action = json.encodeToString(JobAction.serializer(), request.action),
            input = request.input?.let { json.encodeToString(kotlinx.serialization.serializer<Map<String, @kotlinx.serialization.Contextual Any?>>(), it) },
            enabled = true,
            createdAt = System.currentTimeMillis(),
            nextRunAt = nextRun,
            destinationTable = request.destinationTable,
            retentionWindowHours = request.retentionWindowHours,
            compressionEnabled = request.compressionEnabled,
            compressionCycleMs = request.compressionCycleMs,
            failureThreshold = request.failureThreshold,
            retryBackoffMs = request.retryBackoffMs,
            autoDisableMessage = request.autoDisableMessage,
            historyRetentionHours = request.historyRetentionHours
        )
        jobDao.insertJob(job)
        job.toDomain()
    }

    override suspend fun getAllJobs(): List<GuruJob> = withContext(Dispatchers.IO) {
        jobDao.getAllJobs().map { it.toDomain() }
    }

    override fun getAllJobsFlow(): Flow<List<GuruJob>> =
        jobDao.getAllJobsFlow().map { jobs -> jobs.map { it.toDomain() } }

    override suspend fun getEnabledJobs(): List<GuruJob> = withContext(Dispatchers.IO) {
        jobDao.getEnabledJobs().map { it.toDomain() }
    }

    override fun getEnabledJobsFlow(): Flow<List<GuruJob>> =
        jobDao.getEnabledJobsFlow().map { jobs -> jobs.map { it.toDomain() } }

    override suspend fun getDueJobs(): List<GuruJob> = withContext(Dispatchers.IO) {
        jobDao.getDueJobs(System.currentTimeMillis()).map { it.toDomain() }
    }

    override suspend fun getJob(id: String): GuruJob? = withContext(Dispatchers.IO) {
        jobDao.getJobById(id)?.toDomain()
    }

    override suspend fun getJobByName(name: String): GuruJob? = withContext(Dispatchers.IO) {
        jobDao.getJobByName(name)?.toDomain()
    }

    override suspend fun updateJob(id: String, request: CreateJobRequest): GuruJob = withContext(Dispatchers.IO) {
        val existing = jobDao.getJobById(id) ?: throw IllegalArgumentException("Job not found: $id")
        val nextRun = calculateNextRunForConfig(request.scheduleType, request.scheduleConfig)
        
        val updated = existing.copy(
            displayName = request.displayName,
            description = request.description,
            scheduleType = request.scheduleType.name,
            scheduleConfig = request.scheduleConfig,
            action = json.encodeToString(JobAction.serializer(), request.action),
            input = request.input?.let { json.encodeToString(kotlinx.serialization.serializer<Map<String, @kotlinx.serialization.Contextual Any?>>(), it) },
            nextRunAt = nextRun
        )
        jobDao.updateJob(updated)
        updated.toDomain()
    }

    override suspend fun enableJob(id: String): GuruJob = withContext(Dispatchers.IO) {
        jobDao.setEnabled(id, true)
        val job = jobDao.getJobById(id)!!
        val nextRun = calculateNextRun(job.toDomain())
        if (nextRun != null) {
            jobDao.setNextRun(id, nextRun)
        }
        jobDao.getJobById(id)!!.toDomain()
    }

    override suspend fun disableJob(id: String): GuruJob = withContext(Dispatchers.IO) {
        jobDao.setEnabled(id, false)
        jobDao.setNextRun(id, null)
        jobDao.getJobById(id)!!.toDomain()
    }

    override suspend fun deleteJob(id: String) = withContext(Dispatchers.IO) {
        jobDao.deleteJobById(id)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun executeJob(id: String): JobExecutionResult {
        val job = getJob(id) ?: throw IllegalArgumentException("Job not found: $id")

        if (!job.enabled) {
            throw IllegalStateException("Job '${job.name}' is disabled")
        }

        val startTime = System.currentTimeMillis()

        try {
            val result = executeAction(job.action, job.input)
            val executionTime = System.currentTimeMillis() - startTime

            val nextRun = calculateNextRun(job)
            recordSuccess(id, result)
            if (nextRun != null) {
                jobDao.setNextRun(id, nextRun)
            }

            // Write execution history
            historyDao.insert(JobExecutionHistoryEntity(
                id = Uuid.random().toString(),
                jobId = id,
                timestamp = startTime,
                success = true,
                executionTimeMs = executionTime,
                resultSummary = result,
                errorMessage = null,
                compressed = false
            ))

            // Write to destination table if set
            val destTable = job.destinationTable
            if (destTable != null) {
                insertResultIntoDestinationTable(destTable, id, job.name, result, startTime)
            }

            return JobExecutionResult(
                jobId = id,
                jobName = job.name,
                startedAt = startTime,
                completedAt = System.currentTimeMillis(),
                success = true,
                result = result,
                error = null,
                executionTimeMs = executionTime
            )
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            recordFailure(id, e.message ?: "Unknown error")

            // Write execution history
            historyDao.insert(JobExecutionHistoryEntity(
                id = Uuid.random().toString(),
                jobId = id,
                timestamp = startTime,
                success = false,
                executionTimeMs = executionTime,
                resultSummary = null,
                errorMessage = e.message,
                compressed = false
            ))

            // Check failure threshold for auto-disable
            val updatedJob = jobDao.getJobById(id)
            val threshold = updatedJob?.failureThreshold
            if (updatedJob != null && threshold != null && updatedJob.failureCount >= threshold) {
                jobDao.setEnabled(id, false)
                jobDao.setNextRun(id, null)
                com.unuslumen.app.data.jobs.JobScheduler.cancelJob(
                    org.koin.core.context.GlobalContext.get().get<android.content.Context>(),
                    id
                )
                fireAutoDisableNotification(updatedJob.toDomain())
            }

            return JobExecutionResult(
                jobId = id,
                jobName = job.name,
                startedAt = startTime,
                completedAt = System.currentTimeMillis(),
                success = false,
                result = null,
                error = e.message,
                executionTimeMs = executionTime
            )
        }
    }

    override suspend fun recordSuccess(id: String, result: String?) = withContext(Dispatchers.IO) {
        val job = jobDao.getJobById(id) ?: return@withContext
        val nextRun = calculateNextRun(job.toDomain())
        jobDao.recordRun(id, System.currentTimeMillis(), nextRun, result)
    }

    override suspend fun recordFailure(id: String, error: String) = withContext(Dispatchers.IO) {
        jobDao.recordFailure(id, error)
    }

    override suspend fun calculateNextRun(job: GuruJob): Long? {
        return calculateNextRunForConfig(job.scheduleType, job.scheduleConfig)
    }

    override suspend fun getSummary(): JobsSummary = withContext(Dispatchers.IO) {
        val all = jobDao.getAllJobs()
        val enabled = all.filter { it.enabled }
        val nextRun = enabled.mapNotNull { it.nextRunAt }.minOrNull()
        
        JobsSummary(
            totalJobs = all.size,
            enabledJobs = enabled.size,
            oneTimeJobs = all.count { it.scheduleType == ScheduleType.ONE_TIME.name },
            intervalJobs = all.count { it.scheduleType == ScheduleType.INTERVAL.name },
            dailyJobs = all.count { it.scheduleType == ScheduleType.DAILY.name },
            weeklyJobs = all.count { it.scheduleType == ScheduleType.WEEKLY.name },
            monthlyJobs = all.count { it.scheduleType == ScheduleType.MONTHLY.name },
            cronJobs = all.count { it.scheduleType == ScheduleType.CRON.name },
            totalRuns = all.sumOf { it.runCount },
            failedRuns = all.sumOf { it.failureCount },
            nextRun = nextRun
        )
    }

    override suspend fun validateJob(request: CreateJobRequest): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate name
        if (request.name.isBlank()) {
            errors.add("Job name cannot be empty")
        }
        if (!request.name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            errors.add("Job name must start with lowercase letter and contain only lowercase letters, numbers, and underscores")
        }
        
        // Validate action
        if (request.action.type !in listOf("skill", "tool")) {
            errors.add("Invalid action type: ${request.action.type}. Must be 'skill' or 'tool'")
        }
        
        // Validate schedule config
        try {
            validateScheduleConfig(request.scheduleType, request.scheduleConfig)
        } catch (e: Exception) {
            errors.add("Invalid schedule config: ${e.message}")
        }
        
        // Check if target exists
        when (request.action.type) {
            "skill" -> {
                val skill = automationRepository.getAutomationByName(request.action.target)
                if (skill == null) {
                    warnings.add("Target skill '${request.action.target}' does not exist yet")
                }
            }
            "tool" -> {
                val tool = toolRegistry.tools.find { it.descriptor.name == request.action.target }
                if (tool == null) {
                    warnings.add("Target tool '${request.action.target}' does not exist")
                }
            }
        }
        
        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    // Helper methods

    private fun validateScheduleConfig(type: ScheduleType, config: String) {
        when (type) {
            ScheduleType.ONE_TIME -> {
                val parsed = json.decodeFromString<IntervalConfig>(config)
                if (parsed.intervalMs <= 0) {
                    throw IllegalArgumentException("One-time job must have a valid timestamp")
                }
            }
            ScheduleType.INTERVAL -> {
                val parsed = json.decodeFromString<IntervalConfig>(config)
                if (parsed.intervalMs < 60000) {
                    throw IllegalArgumentException("Interval must be at least 1 minute")
                }
            }
            ScheduleType.DAILY -> {
                val parsed = json.decodeFromString<DailyConfig>(config)
                if (parsed.hour !in 0..23 || parsed.minute !in 0..59) {
                    throw IllegalArgumentException("Invalid hour or minute for daily schedule")
                }
            }
            ScheduleType.WEEKLY -> {
                val parsed = json.decodeFromString<WeeklyConfig>(config)
                if (parsed.daysOfWeek.isEmpty() || parsed.daysOfWeek.any { it !in 1..7 }) {
                    throw IllegalArgumentException("Invalid days for weekly schedule")
                }
                if (parsed.hour !in 0..23 || parsed.minute !in 0..59) {
                    throw IllegalArgumentException("Invalid hour or minute for weekly schedule")
                }
            }
            ScheduleType.MONTHLY -> {
                val parsed = json.decodeFromString<MonthlyConfig>(config)
                if (parsed.dayOfMonth !in 1..31) {
                    throw IllegalArgumentException("Invalid day of month")
                }
                if (parsed.hour !in 0..23 || parsed.minute !in 0..59) {
                    throw IllegalArgumentException("Invalid hour or minute for monthly schedule")
                }
            }
            ScheduleType.CRON -> {
                val parsed = json.decodeFromString<CronConfig>(config)
                if (parsed.expression.isBlank()) {
                    throw IllegalArgumentException("Cron expression cannot be empty")
                }
                // Basic cron validation - could be enhanced
                val parts = parsed.expression.split(" ")
                if (parts.size < 5) {
                    throw IllegalArgumentException("Invalid cron expression format")
                }
            }
        }
    }

    private fun calculateNextRunForConfig(type: ScheduleType, config: String): Long? {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        
        return when (type) {
            ScheduleType.ONE_TIME -> {
                val parsed = json.decodeFromString<IntervalConfig>(config)
                if (parsed.intervalMs > now) parsed.intervalMs else null
            }
            ScheduleType.INTERVAL -> {
                val parsed = json.decodeFromString<IntervalConfig>(config)
                now + parsed.initialDelayMs + parsed.intervalMs
            }
            ScheduleType.DAILY -> {
                val parsed = json.decodeFromString<DailyConfig>(config)
                calendar.set(Calendar.HOUR_OF_DAY, parsed.hour)
                calendar.set(Calendar.MINUTE, parsed.minute)
                calendar.set(Calendar.SECOND, 0)
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                }
                calendar.timeInMillis
            }
            ScheduleType.WEEKLY -> {
                val parsed = json.decodeFromString<WeeklyConfig>(config)
                val today = calendar.get(Calendar.DAY_OF_WEEK)
                val targetDay = parsed.daysOfWeek.minBy { day ->
                    val diff = day - today
                    if (diff <= 0) diff + 7 else diff
                }
                calendar.set(Calendar.DAY_OF_WEEK, targetDay)
                calendar.set(Calendar.HOUR_OF_DAY, parsed.hour)
                calendar.set(Calendar.MINUTE, parsed.minute)
                calendar.set(Calendar.SECOND, 0)
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.WEEK_OF_YEAR, 1)
                }
                calendar.timeInMillis
            }
            ScheduleType.MONTHLY -> {
                val parsed = json.decodeFromString<MonthlyConfig>(config)
                calendar.set(Calendar.DAY_OF_MONTH, parsed.dayOfMonth)
                calendar.set(Calendar.HOUR_OF_DAY, parsed.hour)
                calendar.set(Calendar.MINUTE, parsed.minute)
                calendar.set(Calendar.SECOND, 0)
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.MONTH, 1)
                }
                calendar.timeInMillis
            }
            ScheduleType.CRON -> {
                // Simplified cron - just use next day at same time for now
                // A proper cron implementation would parse the expression
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                calendar.timeInMillis
            }
        }
    }

    private suspend fun insertResultIntoDestinationTable(tableName: String, jobId: String, jobName: String, result: String, timestamp: Long) {
        try {
            val guruDb = org.koin.core.context.GlobalContext.get().get<com.unuslumen.app.database.guruDatabase>()
            val db = guruDb.openHelper.writableDatabase

            // Query the actual columns of the destination table
            val columnCursor = db.query("PRAGMA table_info($tableName)")
            val columnNames = mutableListOf<String>()
            columnCursor.use { c ->
                while (c.moveToNext()) {
                    columnNames.add(c.getString(c.getColumnIndexOrThrow("name")))
                }
            }

            if (columnNames.isEmpty()) {
                android.util.Log.e("guru_jobs", "Destination table '$tableName' has no columns or does not exist")
                return
            }

            // Parse the result JSON to extract structured fields
            // RSS results have: {"success":true,"feed":{"title":"...","entries":[{"title":"...","link":"...",...}]}}
            var feedTitle: String? = null
            var firstEntryTitle: String? = null
            var firstEntryLink: String? = null
            try {
                val parsed = json.parseToJsonElement(result) as? JsonObject
                val feed = parsed?.get("feed") as? JsonObject
                feedTitle = (feed?.get("title") as? JsonPrimitive)?.content
                val entries = feed?.get("entries") as? JsonArray
                val firstEntry = entries?.firstOrNull() as? JsonObject
                firstEntryTitle = (firstEntry?.get("title") as? JsonPrimitive)?.content
                firstEntryLink = (firstEntry?.get("link") as? JsonPrimitive)?.content
            } catch (e: Exception) {
                android.util.Log.w("guru_jobs", "Could not parse result JSON for field extraction: ${e.message}")
            }

            // Build the INSERT dynamically based on what columns exist
            val idValue: Any? = Uuid.random().toString()

            // Find the best matching column for each piece of data we want to insert
            val idColumn = columnNames.find { it == "id" } ?: columnNames.first()

            val resultColumn = when {
                "content" in columnNames -> "content"
                "result" in columnNames -> "result"
                "data" in columnNames -> "data"
                "value" in columnNames -> "value"
                "payload" in columnNames -> "payload"
                else -> columnNames.last()
            }

            val timestampColumn = when {
                "timestamp" in columnNames -> "timestamp"
                "polled_at" in columnNames -> "polled_at"
                "createdAt" in columnNames -> "createdAt"
                "created_at" in columnNames -> "created_at"
                "time" in columnNames -> "time"
                else -> null
            }

            // Title column: use the first entry title, fall back to feed title
            val titleColumn = columnNames.find { it == "title" || it == "headline" || it == "name" }
            val titleValue = firstEntryTitle ?: feedTitle

            // URL column: use the first entry link
            val urlColumn = columnNames.find { it == "url" || it == "link" || it == "href" }
            val urlValue = firstEntryLink

            // Source column: use the feed title for a readable source name, fall back to job name
            val sourceColumn = columnNames.find { it == "source" || it == "job_id" || it == "jobId" }
            val sourceValue = feedTitle ?: jobName

            // Build column list and values
            val cols = mutableListOf<String>(idColumn, resultColumn)
            val vals = mutableListOf<Any?>(idValue, result)

            if (timestampColumn != null) {
                cols.add(timestampColumn)
                vals.add(timestamp)
            }

            if (titleColumn != null && titleColumn !in cols && titleValue != null) {
                cols.add(titleColumn)
                vals.add(titleValue)
            }

            if (urlColumn != null && urlColumn !in cols && urlValue != null) {
                cols.add(urlColumn)
                vals.add(urlValue)
            }

            if (sourceColumn != null && sourceColumn !in cols) {
                cols.add(sourceColumn)
                vals.add(sourceValue)
            }

            val placeholders = cols.joinToString(", ") { "?" }
            val columnList = cols.joinToString(", ")
            db.execSQL(
                "INSERT INTO $tableName ($columnList) VALUES ($placeholders)",
                vals.toTypedArray()
            )
            android.util.Log.d("guru_jobs", "Inserted result into $tableName columns: $columnList")
        } catch (e: Exception) {
            android.util.Log.e("guru_jobs", "Failed to insert result into destination table '$tableName': ${e.message}")
        }
    }

    private fun fireAutoDisableNotification(job: GuruJob) {
        try {
            val context = org.koin.core.context.GlobalContext.get().get<android.content.Context>()
            val message = job.autoDisableMessage ?: "Job '${job.name}' has been auto-disabled after ${job.failureThreshold} consecutive failures"
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notification = androidx.core.app.NotificationCompat.Builder(context, "reminders")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Job Auto-Disabled: ${job.name}")
                .setContentText(message)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(job.name.hashCode(), notification)
        } catch (e: Exception) {
            android.util.Log.e("guru_jobs", "Failed to fire auto-disable notification for job '${job.name}': ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeAction(action: JobAction, input: Map<String, Any?>?): String {
        return when (action.type) {
            "skill" -> {
                val params = action.params.mapValues { (_, value) ->
                    input?.get(value.substringAfter("\${'$'}"))?.toString() ?: value
                }
                val result = automationRepository.executeAutomationByName(action.target, params)
                if (result.success) "Skill executed successfully" else "Skill execution failed: ${result.error}"
            }
            "tool" -> {
                val tool = toolRegistry.tools.find { it.descriptor.name == action.target }
                    ?: throw IllegalArgumentException("Tool not found: ${action.target}")
                val params = action.params.mapValues { (_, value) ->
                    (input?.get(value.substringAfter("\${'$'}")) ?: value).toString()
                }
                val args = json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    params
                )
                val argsJsonObject = json.parseToJsonElement(args).jsonObject
                val decodedArgs = tool.decodeArgs(argsJsonObject)
                val result = tool.execute(decodedArgs)
                tool.encodeResult(result).toString()
            }
            else -> throw IllegalArgumentException("Unknown action type: ${action.type}")
        }
    }

    private fun GuruJobEntity.toDomain(): GuruJob {
        val action = try {
            json.decodeFromString<JobAction>(action)
        } catch (e: Exception) {
            JobAction(type = "skill", target = "unknown")
        }
        
        val inputMap = input?.let {
            try {
                @Suppress("UNCHECKED_CAST")
                json.decodeFromString<Map<String, Any?>>(it)
            } catch (e: Exception) {
                null
            }
        }
        
        return GuruJob(
            id = id,
            name = name,
            displayName = displayName,
            description = description,
            scheduleType = ScheduleType.valueOf(scheduleType),
            scheduleConfig = scheduleConfig,
            action = action,
            input = inputMap,
            enabled = enabled,
            createdAt = createdAt,
            lastRunAt = lastRunAt,
            nextRunAt = nextRunAt,
            runCount = runCount,
            lastResult = lastResult,
            failureCount = failureCount,
            lastError = lastError,
            destinationTable = destinationTable,
            retentionWindowHours = retentionWindowHours,
            compressionEnabled = compressionEnabled,
            compressionCycleMs = compressionCycleMs,
            failureThreshold = failureThreshold,
            retryBackoffMs = retryBackoffMs,
            autoDisableMessage = autoDisableMessage,
            historyRetentionHours = historyRetentionHours
        )
    }
}