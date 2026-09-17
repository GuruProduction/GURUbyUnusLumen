package com.unuslumen.app.data.use_case

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.unuslumen.app.data.model.JsonBackupData
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.exception.BackupDataException
import com.unuslumen.app.domain.use_case.`interface`.ExportJsonDataUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory
class ExportJsonDataUseCaseImpl(
    private val context: Context,
    private val database: guruDatabase,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
) : ExportJsonDataUseCase {
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun invoke(
        directoryUri: String,
        exportNotes: Boolean,
        exportTasks: Boolean,
        exportJournal: Boolean,
        exportBookmarks: Boolean,
        encrypted: Boolean,
        password: String?
    ) {
        withContext(ioDispatcher) {
            try {
                val fileName = "guru_Backup_${System.currentTimeMillis()}.json"
                val pickedDir = DocumentFile.fromTreeUri(context, directoryUri.toUri())
                    ?: throw BackupDataException.GenericError()
                val destination = pickedDir.createFile("application/json", fileName)
                    ?: throw BackupDataException.GenericError()

                val notes = if (exportNotes) database.noteDao().getAllFullNotes() else emptyList()
                val noteFolders =
                    if (exportNotes) database.noteDao().getAllNoteFolders().first() else emptyList()
                val tasks =
                    if (exportTasks) database.taskDao().getAllFullTasks() else emptyList()
                val journal =
                    if (exportJournal) database.journalDao().getAllFullEntries() else emptyList()
                val bookmarks = if (exportBookmarks) database.bookmarkDao().getAllFullBookmarks()
                    else emptyList()

                val backupData = JsonBackupData(notes, noteFolders, tasks, journal, bookmarks)

                val outputStream = context.contentResolver.openOutputStream(destination.uri)
                    ?: throw BackupDataException.GenericError()

                outputStream.use {
                    Json.encodeToStream(backupData, it)
                }
            } catch (e: BackupDataException) {
                throw e
            } catch (e: Exception) {
                throw BackupDataException.GenericError()
            }
        }
    }
}
