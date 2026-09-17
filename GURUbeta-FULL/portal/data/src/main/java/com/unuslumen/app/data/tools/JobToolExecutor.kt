package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.jobs.JobScheduler
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.CreateJobRequest
import com.unuslumen.app.domain.model.ScheduleType
import com.unuslumen.app.domain.repository.JobRepository
import kotlinx.serialization.json.Json

class JobToolExecutor(
    private val jobRepository: JobRepository,
    private val context: Context
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        JobToolDefinitions.CREATE_JOB -> createJob(args)
        JobToolDefinitions.LIST_JOBS -> { val jobs = jobRepository.getAllJobs(); val r = ListJobsResult(jobs.map { it.toInfo() }, jobRepository.getSummary()); ToolExecutionResult.success(r, json.encodeToString(ListJobsResult.serializer(), r)) }
        JobToolDefinitions.GET_JOB -> { val job = jobRepository.getJobByName(args["name"] as? String ?: "") ?: jobRepository.getJob(args["name"] as? String ?: "") ?: return ToolExecutionResult.error("Job not found"); val r = GetJobResult(JobDetails(job.id, job.name, job.displayName, job.description, job.scheduleType.name, job.scheduleConfig, JobActionDetails(job.action.type, job.action.target, job.action.params), job.input?.let { json.encodeToString(kotlinx.serialization.serializer<Map<String, @kotlinx.serialization.Contextual Any?>>(), it) }, job.enabled, job.runCount, job.failureCount, job.createdAt, job.lastRunAt, job.nextRunAt, job.lastResult, job.lastError, job.destinationTable, job.retentionWindowHours, job.compressionEnabled, job.compressionCycleMs, job.failureThreshold, job.retryBackoffMs, job.autoDisableMessage, job.historyRetentionHours)); ToolExecutionResult.success(r, json.encodeToString(GetJobResult.serializer(), r)) }
        JobToolDefinitions.DELETE_JOB -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val job = jobRepository.getJobByName(name) ?: jobRepository.getJob(name) ?: return ToolExecutionResult.error("Job not found"); JobScheduler.cancelJob(context, job.id); jobRepository.deleteJob(job.id); val r = DeleteJobResult(job.id, job.name, "Deleted."); ToolExecutionResult.success(r, json.encodeToString(DeleteJobResult.serializer(), r)) }
        JobToolDefinitions.ENABLE_JOB -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val job = jobRepository.getJobByName(name) ?: jobRepository.getJob(name) ?: return ToolExecutionResult.error("Job not found"); val enabled = jobRepository.enableJob(job.id); JobScheduler.scheduleJob(context, enabled); val r = EnableJobResult(job.id, job.name, enabled.nextRunAt, "Enabled."); ToolExecutionResult.success(r, json.encodeToString(EnableJobResult.serializer(), r)) }
        JobToolDefinitions.DISABLE_JOB -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val job = jobRepository.getJobByName(name) ?: jobRepository.getJob(name) ?: return ToolExecutionResult.error("Job not found"); JobScheduler.cancelJob(context, job.id); val disabled = jobRepository.disableJob(job.id); val r = DisableJobResult(job.id, job.name, "Disabled."); ToolExecutionResult.success(r, json.encodeToString(DisableJobResult.serializer(), r)) }
        JobToolDefinitions.RUN_JOB -> { val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'"); val job = jobRepository.getJobByName(name) ?: jobRepository.getJob(name) ?: return ToolExecutionResult.error("Job not found"); val result = jobRepository.executeJob(job.id); val r = RunJobResult(job.id, job.name, result.success, result.result, result.error, result.executionTimeMs, if (result.success) "Executed" else "Failed"); ToolExecutionResult.success(r, json.encodeToString(RunJobResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createJob(args: Map<String, Any?>): ToolExecutionResult {
        val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val displayName = args["displayName"] as? String ?: return ToolExecutionResult.error("Missing 'displayName'")
        val description = args["description"] as? String ?: return ToolExecutionResult.error("Missing 'description'")
        val scheduleTypeStr = args["scheduleType"] as? String ?: return ToolExecutionResult.error("Missing 'scheduleType'")
        val scheduleConfig = args["scheduleConfig"] as? String ?: return ToolExecutionResult.error("Missing 'scheduleConfig'")
        val actionStr = args["action"] as? String ?: return ToolExecutionResult.error("Missing 'action'")
        val input = args["input"] as? String

        val type = try { ScheduleType.valueOf(scheduleTypeStr.uppercase()) } catch (e: Exception) { return ToolExecutionResult.error("Invalid schedule type: $scheduleTypeStr") }
        val parsedAction = try { json.decodeFromString<com.unuslumen.app.domain.model.JobAction>(actionStr) } catch (e: Exception) { return ToolExecutionResult.error("Invalid action JSON: ${e.message}") }
        val parsedInput = input?.let { try { json.decodeFromString<Map<String, Any?>>(it) } catch (e: Exception) { null } }

        val destinationTable = args["destinationTable"] as? String
        val retentionWindowHours = (args["retentionWindowHours"] as? Number)?.toInt()
        val compressionEnabled = args["compressionEnabled"] as? Boolean ?: false
        val compressionCycleMs = (args["compressionCycleMs"] as? Number)?.toLong()
        val failureThreshold = (args["failureThreshold"] as? Number)?.toInt()
        val retryBackoffMs = (args["retryBackoffMs"] as? Number)?.toLong()
        val autoDisableMessage = args["autoDisableMessage"] as? String
        val historyRetentionHours = (args["historyRetentionHours"] as? Number)?.toInt()

        val req = CreateJobRequest(
            name = name, displayName = displayName, description = description,
            scheduleType = type, scheduleConfig = scheduleConfig, action = parsedAction, input = parsedInput,
            destinationTable = destinationTable, retentionWindowHours = retentionWindowHours,
            compressionEnabled = compressionEnabled, compressionCycleMs = compressionCycleMs,
            failureThreshold = failureThreshold, retryBackoffMs = retryBackoffMs,
            autoDisableMessage = autoDisableMessage, historyRetentionHours = historyRetentionHours
        )
        val validation = jobRepository.validateJob(req)
        if (!validation.valid) return ToolExecutionResult.error("Invalid job: ${validation.errors.joinToString("; ")}")
        val job = jobRepository.createJob(req)
        JobScheduler.scheduleJob(context, job)
        val r = CreateJobResult(job.id, job.name, job.displayName, job.scheduleType.name, job.nextRunAt, job.enabled, "Created.")
        return ToolExecutionResult.success(r, json.encodeToString(CreateJobResult.serializer(), r))
    }

    private fun com.unuslumen.app.domain.model.GuruJob.toInfo() = JobInfo(id, name, displayName, description, scheduleType.name, enabled, runCount, nextRunAt)
}