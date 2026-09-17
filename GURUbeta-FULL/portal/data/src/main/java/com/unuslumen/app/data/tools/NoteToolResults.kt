package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder
import kotlinx.serialization.Serializable

@Serializable data class NoteInput(val title: String, val content: String, val folderId: String? = null, val pinned: Boolean = false) : ToolResultData
@Serializable data class SearchNoteFoldersResult(val folders: List<NoteFolder>) : ToolResultData
@Serializable data class SearchNotesResult(val notes: List<Note>) : ToolResultData
@Serializable data class NoteIdResult(val createdNoteId: String) : ToolResultData
@Serializable data class NoteIdsResult(val createdNoteIds: List<String>) : ToolResultData
@Serializable data class NoteResult(val note: Note) : ToolResultData
@Serializable data class CreateNoteFolderResult(val folderId: String) : ToolResultData
@Serializable data class DeleteNoteResult(val deletedNoteId: String) : ToolResultData
@Serializable data class NoteFolderResult(val folder: NoteFolder) : ToolResultData
@Serializable data class DeleteNoteFolderResult(val deletedFolderId: String) : ToolResultData
@Serializable data class GetAllNoteFoldersResult(val folders: List<NoteFolder>) : ToolResultData