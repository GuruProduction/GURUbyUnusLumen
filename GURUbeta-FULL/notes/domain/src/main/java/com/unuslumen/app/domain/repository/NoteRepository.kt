package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder
import kotlinx.coroutines.flow.Flow

interface NoteRepository {

    fun getAllFolderlessNotes(): Flow<List<Note>>

    fun getAllNotes(): Flow<List<Note>>

    suspend fun getNote(id: String): Note?

    suspend fun searchNotes(query: String): List<Note>

    fun getNotesByFolder(folderId: String): Flow<List<Note>>

    suspend fun upsertNote(note: Note, currentFolderId: String? = null): String

    suspend fun upsertNotes(notes: List<Note>): List<String>

    suspend fun deleteNote(note: Note)

    suspend fun insertNoteFolder(folderName: String): String

    suspend fun updateNoteFolder(folder: NoteFolder)

    suspend fun deleteNoteFolder(folder: NoteFolder)

    fun getAllNoteFolders(): Flow<List<NoteFolder>>

    suspend fun getNoteFolder(folderId: String): NoteFolder?

    suspend fun searchFoldersByName(name: String): List<NoteFolder>

}