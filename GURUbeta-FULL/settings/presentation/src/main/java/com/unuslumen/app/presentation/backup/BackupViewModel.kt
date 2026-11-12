// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.exception.BackupDataException
import com.unuslumen.app.domain.model.BackupFormat
import com.unuslumen.app.domain.model.BackupFrequency
import com.unuslumen.app.domain.repository.BackupScheduler
import com.unuslumen.app.domain.repository.FileUtilsRepository
import com.unuslumen.app.domain.use_case.CreateBrainSnapshotUseCase
import com.unuslumen.app.domain.use_case.ExportDataUseCase
import com.unuslumen.app.domain.use_case.ImportDataUseCase
import com.unuslumen.app.domain.use_case.RestoreBrainSnapshotUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.model.PrefsKey.BooleanKey
import com.unuslumen.app.preferences.domain.model.PrefsKey.IntKey
import com.unuslumen.app.preferences.domain.model.PrefsKey.StringKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class BackupViewModel(
    private val exportData: ExportDataUseCase,
    private val importData: ImportDataUseCase,
    private val savePreference: SavePreferenceUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val backupScheduler: BackupScheduler,
    private val fileUtilsRepository: FileUtilsRepository,
    private val createBrainSnapshot: CreateBrainSnapshotUseCase,
    private val restoreBrainSnapshot: RestoreBrainSnapshotUseCase,
) : ViewModel() {

    private val _backupResult = MutableStateFlow<BackupResult>(BackupResult.Idle)
    val backupResult: StateFlow<BackupResult> = _backupResult

    private val _brainSnapshot = MutableStateFlow(BrainSnapshotUiState.Idle)
    val brainSnapshot: StateFlow<BrainSnapshotUiState> = _brainSnapshot

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val backupError = throwable as? BackupDataException ?: BackupDataException.GenericError()
        _backupResult.update { BackupResult.Error(backupError) }
    }

    fun <T> getSettings(key: PrefsKey<T>, defaultValue: T): Flow<T> {
        return getPreference(key, defaultValue)
    }

    fun getAutoBackupFolderPath(): Flow<String?> {
        return getPreference(
            stringPreferencesKey(PrefsConstants.AUTO_BACKUP_FOLDER_URI),
            ""
        ).map { uri ->
            if (uri.isBlank()) null
            else fileUtilsRepository.getPathFromUri(uri)
        }
    }

    fun onEvent(event: BackupEvent) {
        when (event) {
            is BackupEvent.ImportData -> importDatabase(
                uri = event.fileUri,
                format = event.format,
            )

            is BackupEvent.ExportData -> exportDatabase(
                uri = event.directoryUri,
                exportNotes = event.exportNotes,
                exportTasks = event.exportTasks,
                exportJournal = event.exportJournal,
                exportBookmarks = event.exportBookmarks,
                format = event.format,
            )

            is BackupEvent.SetAutoBackupEnabled -> {
                viewModelScope.launch {
                    saveSettings(BooleanKey(PrefsConstants.AUTO_BACKUP_ENABLED), event.enabled)
                    if (!event.enabled) {
                        backupScheduler.cancelBackup()
                    } else {
                        // If enabling, reschedule if folder is already set
                        val folderUri = getPreference(
                            stringPreferencesKey(PrefsConstants.AUTO_BACKUP_FOLDER_URI),
                            ""
                        ).firstOrNull()
                        if (!folderUri.isNullOrBlank()) rescheduleBackup()
                    }
                }
            }

            is BackupEvent.SelectAutoBackupFolder -> {
                viewModelScope.launch {
                    saveSettings(StringKey(PrefsConstants.AUTO_BACKUP_FOLDER_URI), event.folderUri)
                    fileUtilsRepository.takePersistablePermission(event.folderUri)
                }
            }

            is BackupEvent.SaveFrequenciesAndReschedule -> {
                viewModelScope.launch {
                    saveSettings(IntKey(PrefsConstants.AUTO_BACKUP_FREQUENCY), event.frequency.value)
                    saveSettings(IntKey(PrefsConstants.AUTO_BACKUP_FREQUENCY_AMOUNT), event.amount)
                    rescheduleBackup()
                }
            }
        }
    }

    /**
     * Take a brain snapshot into the folder the owner just picked.
     *
     * The keep limit is fixed at five rather than exposed as a control. Five is enough that
     * a mistake noticed a month later still has a snapshot behind it, and the alternative is
     * another setting the owner has to reason about for a feature they press once per phone.
     */
    fun createSnapshot(folderUri: String, passphrase: String) {
        viewModelScope.launch {
            _brainSnapshot.value = BrainSnapshotUiState(busy = true)
            val outcome = createBrainSnapshot(folderUri, passphrase, keep = SNAPSHOT_KEEP)
            _brainSnapshot.value = BrainSnapshotUiState(
                busy = false,
                succeeded = outcome.succeeded,
                message = if (outcome.succeeded) {
                    buildString {
                        append("Saved ${outcome.fileName} (${outcome.bytes} bytes) and verified it ")
                        append("restores: ${outcome.totalRows} rows across ${outcome.tables} tables.")
                        if (outcome.prunedCount > 0) {
                            append(" Removed ${outcome.prunedCount} older snapshot(s).")
                        }
                    }
                } else {
                    outcome.message
                },
            )
        }
    }

    /**
     * Restore the brain from a snapshot file the owner picked.
     *
     * A successful restore ends by telling the owner to restart GURU, and that is not
     * softened: Room holds the old database open until the process dies, so the restored
     * file is on disk but not yet in use. Saying "restored" without the restart would be a
     * lie the owner would discover by finding their old conversations still there.
     */
    fun restoreSnapshot(fileUri: String, passphrase: String) {
        viewModelScope.launch {
            _brainSnapshot.value = BrainSnapshotUiState(busy = true)
            val outcome = restoreBrainSnapshot(fileUri, passphrase)
            _brainSnapshot.value = BrainSnapshotUiState(
                busy = false,
                succeeded = outcome.succeeded,
                message = if (outcome.succeeded) {
                    buildString {
                        append(outcome.message)
                        append(" ${outcome.totalRows} rows across ${outcome.tables} tables are now on disk.")
                    }
                } else {
                    outcome.message
                },
            )
        }
    }

    private suspend fun <T> saveSettings(key: PrefsKey<T>, value: T) {
        savePreference(key, value)
    }

    private suspend fun rescheduleBackup() {
        val isEnabled = getPreference(
            BooleanKey(PrefsConstants.AUTO_BACKUP_ENABLED),
            false
        ).first()
        val folderUri = getPreference(
            stringPreferencesKey(PrefsConstants.AUTO_BACKUP_FOLDER_URI),
            ""
        ).first()

        if (isEnabled && folderUri.isNotBlank()) {
            val frequencyValue = getPreference(
                IntKey(PrefsConstants.AUTO_BACKUP_FREQUENCY),
                BackupFrequency.DAILY.value
            ).first()
            val frequencyAmount = getPreference(
                IntKey(PrefsConstants.AUTO_BACKUP_FREQUENCY_AMOUNT),
                1
            ).first()

            val frequency = BackupFrequency.entries.firstOrNull { it.value == frequencyValue }
                ?: BackupFrequency.DAILY

            backupScheduler.scheduleBackup(
                folderUri = folderUri,
                frequency = frequency,
                frequencyAmount = frequencyAmount
            )
        } else {
            backupScheduler.cancelBackup()
        }
    }

    private fun importDatabase(
        uri: String,
        format: BackupFormat,
    ) {
        viewModelScope.launch(exceptionHandler) {
            _backupResult.update { BackupResult.Loading }
            importData(
                fileUri = uri,
                format = format,
            )
            _backupResult.update { BackupResult.ImportSuccess }
        }
    }

    private fun exportDatabase(
        uri: String,
        exportNotes: Boolean,
        exportTasks: Boolean,
        exportJournal: Boolean,
        exportBookmarks: Boolean,
        format: BackupFormat,
    ) {
        viewModelScope.launch(exceptionHandler) {
            _backupResult.update { BackupResult.Loading }
            fileUtilsRepository.takePersistablePermission(uri)
            exportData(
                directoryUri = uri,
                exportNotes = exportNotes,
                exportTasks = exportTasks,
                exportJournal = exportJournal,
                exportBookmarks = exportBookmarks,
                format = format,
            )
            _backupResult.update { BackupResult.ExportSuccess }
        }
    }

    companion object {
        /** How many snapshots a folder keeps before the oldest is removed. */
        const val SNAPSHOT_KEEP: Int = 5
    }

}
