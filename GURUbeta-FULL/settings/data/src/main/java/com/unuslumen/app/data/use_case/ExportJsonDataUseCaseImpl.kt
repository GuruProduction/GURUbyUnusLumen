// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
    ) {
        withContext(ioDispatcher) {
            try {
                val fileName = "guru_Backup_${System.currentTimeMillis()}.json"
                val pickedDir = DocumentFile.fromTreeUri(context, directoryUri.toUri())
                    ?: throw BackupDataException.InvalidBackupLocation(directoryUri)
                val destination = pickedDir.createFile("application/json", fileName)
                    ?: throw BackupDataException.CouldNotCreateFile(
                        fileName = fileName,
                        parent = pickedDir.name ?: directoryUri,
                    )

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
                    ?: throw BackupDataException.CouldNotWriteFile(
                        fileName = destination.name ?: fileName,
                        parent = pickedDir.name ?: directoryUri,
                    )

                outputStream.use {
                    // ENCODE_DEFAULTS IS LOAD-BEARING, and its absence was a bug waiting to
                    // happen. Every field of JsonBackupData has an empty default, and with
                    // defaults omitted an export where the owner chose nothing, or chose only
                    // content they have none of, serialises to "{}". The import refuses a
                    // document with none of the five keys, which is correct, but it must not
                    // refuse a file this app just wrote. Emitting all five keys always keeps
                    // the writer and the reader agreeing about what a valid export looks like.
                    EXPORT_JSON.encodeToStream(backupData, it)
                }
            } catch (e: BackupDataException) {
                throw e
            } catch (e: Exception) {
                throw BackupDataException.GenericError()
            }
        }
    }

    companion object {
        /**
         * The writer's encoder, built once.
         *
         * A Json instance carries lookup tables that are expensive to build, so creating one per
         * export writes the same configuration repeatedly for one file. Held here so the
         * configuration is stated once and the cost is paid once.
         */
        val EXPORT_JSON: Json = Json { encodeDefaults = true }
    }
}
