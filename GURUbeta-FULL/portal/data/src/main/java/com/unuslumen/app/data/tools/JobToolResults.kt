package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class CreateJobResult(val jobId: String, val name: String, val displayName: String, val scheduleType: String, val nextRunAt: Long?, val enabled: Boolean, val message: String) : ToolResultData
@Serializable data class ListJobsResult(val jobs: List<JobInfo>, val summary: com.unuslumen.app.domain.model.JobsSummary) : ToolResultData
@Serializable data class JobInfo(val id: String, val name: String, val displayName: String, val description: String, val scheduleType: String, val enabled: Boolean, val runCount: Int, val nextRunAt: Long?) : ToolResultData
@Serializable data class GetJobResult(val job: JobDetails) : ToolResultData
@Serializable data class JobActionDetails(val type: String, val target: String, val params: Map<String, String> = emptyMap()) : ToolResultData
@Serializable data class JobDetails(val id: String, val name: String, val displayName: String, val description: String, val scheduleType: String, val scheduleConfig: String, val action: JobActionDetails, val input: String?, val enabled: Boolean, val runCount: Int, val failureCount: Int, val createdAt: Long, val lastRunAt: Long?, val nextRunAt: Long?, val lastResult: String?, val lastError: String?, val destinationTable: String? = null, val retentionWindowHours: Int? = null, val compressionEnabled: Boolean = false, val compressionCycleMs: Long? = null, val failureThreshold: Int? = null, val retryBackoffMs: Long? = null, val autoDisableMessage: String? = null, val historyRetentionHours: Int? = null) : ToolResultData
@Serializable data class DeleteJobResult(val jobId: String, val name: String, val message: String) : ToolResultData
@Serializable data class EnableJobResult(val jobId: String, val name: String, val nextRunAt: Long?, val message: String) : ToolResultData
@Serializable data class DisableJobResult(val jobId: String, val name: String, val message: String) : ToolResultData
@Serializable data class RunJobResult(val jobId: String, val jobName: String, val success: Boolean, val result: String?, val error: String?, val executionTimeMs: Long, val message: String) : ToolResultData