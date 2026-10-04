// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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