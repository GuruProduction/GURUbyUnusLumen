package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a scheduled job defined by Guru.
 * Jobs are recurring tasks that run on a schedule using WorkManager.
 */
@Entity(
    tableName = "guru_jobs",
    indices = [
        Index(value = ["name"], name = "index_guru_jobs_name"),
        Index(value = ["enabled"], name = "index_guru_jobs_enabled"),
        Index(value = ["nextRunAt"], name = "index_guru_jobs_nextRunAt")
    ]
)
data class GuruJobEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val scheduleType: String, // ONE_TIME, INTERVAL, DAILY, WEEKLY, MONTHLY, CRON
    val scheduleConfig: String, // JSON config for schedule
    val action: String, // JSON action definition (skill or tool to run)
    val input: String?, // JSON input data for the action
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val runCount: Int = 0,
    val lastResult: String? = null, // JSON result of last run
    @ColumnInfo(defaultValue = "0")
    val failureCount: Int = 0,
    val lastError: String? = null,
    val destinationTable: String? = null,
    val retentionWindowHours: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val compressionEnabled: Boolean = false,
    val compressionCycleMs: Long? = null,
    val failureThreshold: Int? = null,
    val retryBackoffMs: Long? = null,
    val autoDisableMessage: String? = null,
    val historyRetentionHours: Int? = null
)