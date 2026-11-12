// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.use_case

import android.content.Context
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.room.withTransaction
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.database.entity.toTask
import com.unuslumen.app.domain.exception.BackupDataException
import com.unuslumen.app.domain.use_case.UpsertTaskUseCase
import com.unuslumen.app.domain.use_case.`interface`.ImportJsonDataUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import kotlin.uuid.Uuid

@Factory
class ImportJsonDataUseCaseImpl(
    private val context: Context,
    private val database: guruDatabase,
    private val upsertTaskUseCase: UpsertTaskUseCase,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
): ImportJsonDataUseCase {

    override suspend fun invoke(fileUri: String) {
        withContext(ioDispatcher) {
            try {
                // READ THE TEXT, THEN DECIDE WHETHER IT IS OURS. This used to decode straight
                // into JsonBackupData, whose five lists all default to empty, so EVERY valid
                // JSON object decoded successfully: a config file, a contacts dump, the sealed
                // snapshot's manifest. The import then wrote nothing and the screen said
                // "Successfully imported data". Somebody could believe their journal was
                // restored and delete the file it came from.
                //
                // The decision itself lives in ContentExportDocument, where it is pure and can
                // be proved with a string. This method keeps only the parts that need a content
                // resolver and a database.
                val text = context.contentResolver.openInputStream(fileUri.toUri())?.use {
                    it.readBytes().decodeToString()
                } ?: throw BackupDataException.CouldNotReadFile

                val backupData = ContentExportDocument.parse(text)

                database.withTransaction {
                    val noteFolderIdMap = HashMap<String, String>()
                    val updatedNoteFolders = backupData.noteFolders.map { folder ->
                        val id = if (folder.id.isDigitsOnly()) {
                            Uuid.random().toString().also { noteFolderIdMap[folder.id] = it }
                        } else {
                            folder.id
                        }
                        folder.copy(id = id)
                    }
                    database.noteDao().upsertNoteFolders(updatedNoteFolders)

                    val updatedNotes = backupData.notes.map { note ->
                        val newFolderId =
                            if (note.folderId?.isDigitsOnly() == true) noteFolderIdMap[note.folderId]
                            else note.folderId.takeIfNotNull()
                        note.copy(folderId = newFolderId, id = note.id.toUuidIfNumber())
                    }
                    database.noteDao().upsertNotes(updatedNotes)

                    backupData.tasks.forEach {
                        upsertTaskUseCase(
                            task = it.toTask().copy(id = it.id.toUuidIfNumber()),
                            updateWidget = false

                        )
                    }

                    val updatedJournalEntries = backupData.journal.map { entry ->
                        entry.copy(id = entry.id.toUuidIfNumber())
                    }
                    database.journalDao().upsertEntries(updatedJournalEntries)

                    val updatedBookmarks = backupData.bookmarks.map { bookmark ->
                        bookmark.copy(id = bookmark.id.toUuidIfNumber())
                    }
                    database.bookmarkDao().upsertBookmarks(updatedBookmarks)
                }
            } catch (e: BackupDataException) {
                // Kept as its own clause so a refusal reaches the screen with the reason it
                // carries. Without it, a file that is not an export would be reported as a
                // generic failure and the person would have no idea which file to pick instead.
                throw e
            } catch (_: Exception) {
                throw BackupDataException.GenericError()
            }
        }
    }

    private fun String?.takeIfNotNull(): String? {
        return if (this == "null") null else this
    }

    // to handle older backup files where id was an integer
    private fun String.toUuidIfNumber(): String {
        return if (this.isDigitsOnly()) {
            Uuid.random().toString()
        } else {
            this
        }
    }

}
