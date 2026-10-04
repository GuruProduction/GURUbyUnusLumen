// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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