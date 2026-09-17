package com.unuslumen.app.data.use_case

import android.content.Context
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.room.withTransaction
import com.unuslumen.app.data.model.JsonBackupData
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.database.entity.toTask
import com.unuslumen.app.domain.exception.BackupDataException
import com.unuslumen.app.domain.use_case.UpsertTaskUseCase
import com.unuslumen.app.domain.use_case.`interface`.ImportJsonDataUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
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

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun invoke(
        fileUri: String,
        encrypted: Boolean,
        password: String?
    ) {
        withContext(ioDispatcher) {
            try {
                val json = Json {
                    ignoreUnknownKeys = true
                }
                val backupData = context.contentResolver.openInputStream(fileUri.toUri())?.use {
                        json.decodeFromStream<JsonBackupData>(it)
                    } ?: throw BackupDataException.CouldNotReadFile

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
            } catch (_: SerializationException) {
                throw BackupDataException.CouldNotReadFile
            } catch (e: BackupDataException) {
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
