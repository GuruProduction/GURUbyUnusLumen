package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface JobRepository {
    
    /**
     * Create a new job.
     */
    suspend fun createJob(request: CreateJobRequest): GuruJob
    
    /**
     * Get all jobs.
     */
    suspend fun getAllJobs(): List<GuruJob>
    
    /**
     * Get all jobs as a flow.
     */
    fun getAllJobsFlow(): Flow<List<GuruJob>>
    
    /**
     * Get enabled jobs.
     */
    suspend fun getEnabledJobs(): List<GuruJob>
    
    /**
     * Get enabled jobs as a flow.
     */
    fun getEnabledJobsFlow(): Flow<List<GuruJob>>
    
    /**
     * Get jobs that are due to run.
     */
    suspend fun getDueJobs(): List<GuruJob>
    
    /**
     * Get a job by ID.
     */
    suspend fun getJob(id: String): GuruJob?
    
    /**
     * Get a job by name.
     */
    suspend fun getJobByName(name: String): GuruJob?
    
    /**
     * Update a job.
     */
    suspend fun updateJob(id: String, request: CreateJobRequest): GuruJob
    
    /**
     * Enable a job.
     */
    suspend fun enableJob(id: String): GuruJob
    
    /**
     * Disable a job.
     */
    suspend fun disableJob(id: String): GuruJob
    
    /**
     * Delete a job.
     */
    suspend fun deleteJob(id: String)
    
    /**
     * Execute a job.
     */
    suspend fun executeJob(id: String): JobExecutionResult
    
    /**
     * Record a successful run.
     */
    suspend fun recordSuccess(id: String, result: String?)
    
    /**
     * Record a failed run.
     */
    suspend fun recordFailure(id: String, error: String)
    
    /**
     * Calculate next run time for a job.
     */
    suspend fun calculateNextRun(job: GuruJob): Long?
    
    /**
     * Get summary statistics.
     */
    suspend fun getSummary(): JobsSummary
    
    /**
     * Validate a job definition.
     */
    suspend fun validateJob(request: CreateJobRequest): ValidationResult
}