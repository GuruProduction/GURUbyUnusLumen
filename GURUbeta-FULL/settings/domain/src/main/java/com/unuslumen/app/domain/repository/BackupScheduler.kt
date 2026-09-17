package com.unuslumen.app.domain.repository

interface BackupScheduler {
    suspend fun scheduleBackup(
        folderUri: String,
        frequency: com.unuslumen.app.domain.model.BackupFrequency,
        frequencyAmount: Int
    )
    suspend fun cancelBackup()
}

