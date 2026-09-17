package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "job_execution_history",
    indices = [
        Index(value = ["jobId"], name = "index_job_execution_history_jobId"),
        Index(value = ["timestamp"], name = "index_job_execution_history_timestamp")
    ]
)
data class JobExecutionHistoryEntity(
    @PrimaryKey
    val id: String,
    val jobId: String,
    val timestamp: Long,
    val success: Boolean,
    val executionTimeMs: Long,
    val resultSummary: String?,
    val errorMessage: String?,
    @ColumnInfo(defaultValue = "0")
    val compressed: Boolean = false
)