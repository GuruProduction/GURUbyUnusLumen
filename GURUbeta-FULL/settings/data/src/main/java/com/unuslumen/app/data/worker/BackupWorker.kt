// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unuslumen.app.domain.exception.BackupDataException
import com.unuslumen.app.domain.model.BackupFormat
import com.unuslumen.app.domain.use_case.ExportDataUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.firstOrNull
import org.koin.android.annotation.KoinWorker

@KoinWorker
class BackupWorker(
    private val exportData: ExportDataUseCase,
    private val getPreference: GetPreferenceUseCase,
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {


    override suspend fun doWork(): Result {
        val folderUri = getPreference(
            stringPreferencesKey(PrefsConstants.AUTO_BACKUP_FOLDER_URI),
            ""
        ).firstOrNull()

        if (folderUri.isNullOrBlank()) return Result.failure()

        return try {
            exportData(
                directoryUri = folderUri,
                exportNotes = true,
                exportTasks = true,
                exportJournal = true,
                exportBookmarks = true,
                format = BackupFormat.JSON,
                encrypted = false,
                password = null
            )
            Result.success()
        } catch (_: BackupDataException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "auto_backup_work"
    }
}
