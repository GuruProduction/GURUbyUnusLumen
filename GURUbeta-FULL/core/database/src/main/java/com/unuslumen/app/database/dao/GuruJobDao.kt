package com.unuslumen.app.database.dao

import androidx.room.*
import com.unuslumen.app.database.entity.GuruJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruJobDao {
    
    @Query("SELECT * FROM guru_jobs ORDER BY name ASC")
    suspend fun getAllJobs(): List<GuruJobEntity>
    
    @Query("SELECT * FROM guru_jobs ORDER BY name ASC")
    fun getAllJobsFlow(): Flow<List<GuruJobEntity>>
    
    @Query("SELECT * FROM guru_jobs WHERE enabled = 1 ORDER BY nextRunAt ASC")
    suspend fun getEnabledJobs(): List<GuruJobEntity>
    
    @Query("SELECT * FROM guru_jobs WHERE enabled = 1 ORDER BY nextRunAt ASC")
    fun getEnabledJobsFlow(): Flow<List<GuruJobEntity>>
    
    @Query("SELECT * FROM guru_jobs WHERE nextRunAt IS NOT NULL AND nextRunAt <= :timestamp AND enabled = 1")
    suspend fun getDueJobs(timestamp: Long): List<GuruJobEntity>
    
    @Query("SELECT * FROM guru_jobs WHERE id = :id")
    suspend fun getJobById(id: String): GuruJobEntity?
    
    @Query("SELECT * FROM guru_jobs WHERE name = :name")
    suspend fun getJobByName(name: String): GuruJobEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: GuruJobEntity)
    
    @Update
    suspend fun updateJob(job: GuruJobEntity)
    
    @Query("UPDATE guru_jobs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
    
    @Query("UPDATE guru_jobs SET lastRunAt = :timestamp, nextRunAt = :nextRun, runCount = runCount + 1, lastResult = :result, lastError = NULL WHERE id = :id")
    suspend fun recordRun(id: String, timestamp: Long, nextRun: Long?, result: String?)
    
    @Query("UPDATE guru_jobs SET failureCount = failureCount + 1, lastError = :error, lastResult = NULL WHERE id = :id")
    suspend fun recordFailure(id: String, error: String)
    
    @Query("UPDATE guru_jobs SET nextRunAt = :nextRun WHERE id = :id")
    suspend fun setNextRun(id: String, nextRun: Long?)
    
    @Delete
    suspend fun deleteJob(job: GuruJobEntity)
    
    @Query("DELETE FROM guru_jobs WHERE id = :id")
    suspend fun deleteJobById(id: String)
}