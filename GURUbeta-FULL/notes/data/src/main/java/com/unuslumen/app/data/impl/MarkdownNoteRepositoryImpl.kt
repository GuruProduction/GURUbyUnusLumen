package com.unuslumen.app.data.impl

import android.net.Uri
import androidx.core.net.toUri
import com.unuslumen.app.data.storage.MarkdownFileManager
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow

class MarkdownNoteRepositoryImpl(
    private val markdownFileManager: MarkdownFileManager,
    private val rootUri: Uri,
) : NoteRepository {

    override fun getAllFolderlessNotes(): Flow<List<Note>> {
        return markdownFileManager.getFolderNotesFlow(rootUri)
    }

    override fun getAllNotes(): Flow<List<Note>> {
        return markdownFileManager.getAllNotesFlow(rootUri)
    }

    override suspend fun getNote(id: String): Note {
        return markdownFileManager.getNote(id.toUri())
    }

    override suspend fun searchNotes(query: String): List<Note> {
        return markdownFileManager.searchNotes(query, rootUri)
    }

    override fun getNotesByFolder(folderId: String): Flow<List<Note>> {
        return markdownFileManager.getFolderNotesFlow(folderId.toUri())
    }

    override suspend fun upsertNote(note: Note, currentFolderId: String?): String {
        return markdownFileManager.upsertNote(note, currentFolderId, rootUri)
    }

    override suspend fun upsertNotes(notes: List<Note>): List<String> {
        return notes.map {
            upsertNote(it, null)
        }
    }

    override suspend fun deleteNote(note: Note) {
        markdownFileManager.deleteNote(note, rootUri)
    }

    override suspend fun insertNoteFolder(folderName: String): String {
        return markdownFileManager.createFolder(folderName, rootUri)
    }

    override suspend fun updateNoteFolder(folder: NoteFolder) {
        markdownFileManager.updateFolder(folder.id.toUri(), folder.name.trim(), rootUri)
    }

    override suspend fun deleteNoteFolder(folder: NoteFolder) {
        markdownFileManager.deleteFolder(folder.id.toUri(), rootUri)
    }

    override fun getAllNoteFolders(): Flow<List<NoteFolder>> {
        return markdownFileManager.getFolderFoldersFlow(rootUri)
    }

    override suspend fun getNoteFolder(folderId: String): NoteFolder? {
        if (folderId == rootUri.toString()) return null
        return markdownFileManager.getFolder(folderId.toUri())
    }

    override suspend fun searchFoldersByName(name: String): List<NoteFolder> {
        return markdownFileManager.searchFolderByName(name, rootUri)
    }

}