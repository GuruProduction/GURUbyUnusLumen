package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add new columns to guru_jobs
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN destinationTable TEXT")
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN retentionWindowHours INTEGER")
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN compressionEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN compressionCycleMs INTEGER")
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN failureThreshold INTEGER")
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN retryBackoffMs INTEGER")
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN autoDisableMessage TEXT")
        db.execSQL("ALTER TABLE guru_jobs ADD COLUMN historyRetentionHours INTEGER")

        // Create job_execution_history table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS job_execution_history (
                id TEXT NOT NULL PRIMARY KEY,
                jobId TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                success INTEGER NOT NULL,
                executionTimeMs INTEGER NOT NULL,
                resultSummary TEXT,
                errorMessage TEXT,
                compressed INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_job_execution_history_jobId ON job_execution_history(jobId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_job_execution_history_timestamp ON job_execution_history(timestamp)")
    }
}