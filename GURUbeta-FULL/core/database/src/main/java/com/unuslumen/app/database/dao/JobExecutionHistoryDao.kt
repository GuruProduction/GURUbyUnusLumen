// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.JobExecutionHistoryEntity

@Dao
interface JobExecutionHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: JobExecutionHistoryEntity)

    @Query("SELECT * FROM job_execution_history WHERE jobId = :jobId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByJobId(jobId: String, limit: Int): List<JobExecutionHistoryEntity>

    @Query("SELECT * FROM job_execution_history WHERE jobId = :jobId AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getByJobIdSince(jobId: String, since: Long): List<JobExecutionHistoryEntity>

    @Query("DELETE FROM job_execution_history WHERE jobId = :jobId")
    suspend fun deleteByJobId(jobId: String)

    @Query("UPDATE job_execution_history SET resultSummary = :compressedResult, compressed = 1 WHERE id = :id")
    suspend fun markCompressed(id: String, compressedResult: String)

    @Query("SELECT * FROM job_execution_history WHERE jobId = :jobId AND compressed = 0 AND timestamp < :timestamp")
    suspend fun getUncompressedOlderThan(jobId: String, timestamp: Long): List<JobExecutionHistoryEntity>
}