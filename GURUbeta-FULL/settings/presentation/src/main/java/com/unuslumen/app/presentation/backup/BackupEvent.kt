package com.unuslumen.app.presentation.backup

import com.unuslumen.app.domain.model.BackupFormat
import com.unuslumen.app.domain.model.BackupFrequency

sealed class BackupEvent {
    data class ImportData(
        val fileUri: String,
        val format: BackupFormat,
        val encrypted: Boolean,
        val password: String
    ) : BackupEvent()

    data class ExportData(
        val directoryUri: String,
        val exportNotes: Boolean,
        val exportTasks: Boolean,
        val exportJournal: Boolean,
        val exportBookmarks: Boolean,
        val format: BackupFormat,
        val encrypted: Boolean,
        val password: String
    ) : BackupEvent()

    data class SetAutoBackupEnabled(
        val enabled: Boolean
    ) : BackupEvent()

    data class SelectAutoBackupFolder(
        val folderUri: String,
    ) : BackupEvent()

    data class SaveFrequenciesAndReschedule(
        val frequency: BackupFrequency,
        val amount: Int
    ) : BackupEvent()
}