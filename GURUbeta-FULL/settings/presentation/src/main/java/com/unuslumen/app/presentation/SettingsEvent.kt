package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.BackupFormat

sealed class SettingsEvent {
    data class ImportData(
        val fileUri: String,
        val format: BackupFormat,
        val encrypted: Boolean,
        val password: String
    ) :
        SettingsEvent()

    data class ExportData(
        val directoryUri: String,
        val exportNotes: Boolean,
        val exportTasks: Boolean,
        val exportJournal: Boolean,
        val exportBookmarks: Boolean,
        val format: BackupFormat,
        val encrypted: Boolean,
        val password: String,
    ) : SettingsEvent()
}