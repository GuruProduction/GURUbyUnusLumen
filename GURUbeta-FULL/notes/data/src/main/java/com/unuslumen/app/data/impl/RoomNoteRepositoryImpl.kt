package com.unuslumen.app.data.impl

import com.unuslumen.app.database.dao.NoteDao
import com.unuslumen.app.database.entity.NoteFolderEntity
import com.unuslumen.app.database.entity.toNote
import com.unuslumen.app.database.entity.toNoteEntity
import com.unuslumen.app.database.entity.toNoteFolder
import com.unuslumen.app.database.entity.toNoteFolderEntity
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.domain.repository.NoteRepository
import com.unuslumen.app.util.errors.NoteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import kotlin.uuid.Uuid

class RoomNoteRepositoryImpl(
    private val noteDao: NoteDao,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
) : NoteRepository {

    override fun getAllFolderlessNotes(): Flow<List<Note>> {
        return noteDao.getAllFolderlessNotes()
            .map { notes ->
                notes.map {
                    it.toNote()
                }
            }
            .flowOn(ioDispatcher)
    }

    override fun getAllNotes(): Flow<List<Note>> {
        return noteDao.getAllNotes()
            .map { notes ->
                notes.map {
                    it.toNote()
                }
            }
            .flowOn(ioDispatcher)
    }

    override suspend fun getNote(id: String): Note? {
        return withContext(ioDispatcher) {
            noteDao.getNote(id)?.toNote()
        }
    }

    override suspend fun searchNotes(query: String): List<Note> {
        return withContext(ioDispatcher) {
            noteDao.getNotesByTitle(query).map {
                it.toNote()
            }
        }
    }

    override fun getNotesByFolder(folderId: String): Flow<List<Note>> {
        return noteDao.getNotesByFolder(folderId)
            .flowOn(ioDispatcher)
            .map { notes ->
                notes.map { it.toNote() }
            }
    }

    override suspend fun upsertNote(note: Note, currentFolderId: String?): String {
        return withContext(ioDispatcher) {
            val id = note.id.ifBlank { Uuid.random().toString() }
            noteDao.upsertNote(note.copy(id = id).toNoteEntity())
            id
        }
    }

    override suspend fun upsertNotes(notes: List<Note>): List<String> {
        return withContext(ioDispatcher) {
            val notesWithIds = notes.map {
                it.copy(id = it.id.ifBlank { Uuid.random().toString() })
            }
            noteDao.upsertNotes(notesWithIds.map { it.toNoteEntity() })
            notesWithIds.map { it.id }
        }
    }

    override suspend fun deleteNote(note: Note) {
        withContext(ioDispatcher) {
            noteDao.deleteNote(note.toNoteEntity())
        }
    }

    override suspend fun insertNoteFolder(folderName: String): String {
        return withContext(ioDispatcher) {
            if (noteDao.getNoteFolderByName(folderName) != null) {
                throw NoteException.FolderWithSameNameExists
            }
            val folderEntity = NoteFolderEntity(id = Uuid.random().toString(), name = folderName)
            noteDao.insertNoteFolder(folderEntity)
            folderEntity.id
        }
    }

    override suspend fun updateNoteFolder(folder: NoteFolder) {
        withContext(ioDispatcher) {
            val existingFolder = noteDao.getNoteFolderByName(folder.name)
            if (existingFolder != null && existingFolder.id != folder.id) {
                throw NoteException.FolderWithSameNameExists
            }
            noteDao.updateNoteFolder(folder.toNoteFolderEntity())
        }
    }

    override suspend fun deleteNoteFolder(folder: NoteFolder) {
        withContext(ioDispatcher) {
            noteDao.deleteFolderAndNotes(folder.id)
        }
    }

    override fun getAllNoteFolders(): Flow<List<NoteFolder>> {
        return noteDao.getAllNoteFolders()
            .flowOn(ioDispatcher)
            .map { folders ->
                folders.map { it.toNoteFolder() }
            }
    }

    override suspend fun getNoteFolder(folderId: String): NoteFolder? {
        return withContext(ioDispatcher) {
            noteDao.getNoteFolder(folderId)?.toNoteFolder()
        }
    }

    override suspend fun searchFoldersByName(name: String): List<NoteFolder> {
        return withContext(ioDispatcher) {
            noteDao.searchFolderByName(name).map { it.toNoteFolder() }
        }
    }
}