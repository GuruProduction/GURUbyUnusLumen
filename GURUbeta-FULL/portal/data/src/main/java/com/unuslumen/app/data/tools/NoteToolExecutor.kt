package com.unuslumen.app.data.tools

import com.unuslumen.app.data.nowMillis
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.use_case.CreateNoteFolderUseCase
import com.unuslumen.app.domain.use_case.DeleteNoteFolderUseCase
import com.unuslumen.app.domain.use_case.DeleteNoteUseCase
import com.unuslumen.app.domain.use_case.GetAllNoteFoldersUseCase
import com.unuslumen.app.domain.use_case.GetNoteFolderUseCase
import com.unuslumen.app.domain.use_case.GetNoteUseCase
import com.unuslumen.app.domain.use_case.SearchNoteFoldersByNameUseCase
import com.unuslumen.app.domain.use_case.SearchNotesUseCase
import com.unuslumen.app.domain.use_case.UpdateNoteFolderUseCase
import com.unuslumen.app.domain.use_case.UpsertNoteUseCase
import com.unuslumen.app.domain.use_case.UpsertNotesUseCase
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class NoteToolExecutor(
    private val upsertNote: UpsertNoteUseCase,
    private val upsertNotes: UpsertNotesUseCase,
    private val searchNotesByName: SearchNotesUseCase,
    private val getNote: GetNoteUseCase,
    private val deleteNoteUseCase: DeleteNoteUseCase,
    private val createFolderUseCase: CreateNoteFolderUseCase,
    private val searchNoteFoldersByName: SearchNoteFoldersByNameUseCase,
    private val getNoteFolder: GetNoteFolderUseCase,
    private val updateNoteFolderUseCase: UpdateNoteFolderUseCase,
    private val deleteNoteFolderUseCase: DeleteNoteFolderUseCase,
    private val getAllNoteFoldersUseCase: GetAllNoteFoldersUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        NoteToolDefinitions.SEARCH_NOTES -> searchNotes(args)
        NoteToolDefinitions.CREATE_NOTE -> createNote(args)
        NoteToolDefinitions.CREATE_MULTIPLE_NOTES -> createMultipleNotes(args)
        NoteToolDefinitions.GET_NOTE_BY_ID -> getNoteById(args)
        NoteToolDefinitions.SEARCH_NOTE_FOLDERS -> searchFolders(args)
        NoteToolDefinitions.CREATE_NOTE_FOLDER -> createFolder(args)
        NoteToolDefinitions.UPDATE_NOTE -> updateNote(args)
        NoteToolDefinitions.DELETE_NOTE -> deleteNote(args)
        NoteToolDefinitions.GET_ALL_NOTES -> getAllNotes()
        NoteToolDefinitions.GET_NOTES_BY_FOLDER -> getNotesByFolder(args)
        NoteToolDefinitions.UPDATE_NOTE_FOLDER -> updateNoteFolder(args)
        NoteToolDefinitions.DELETE_NOTE_FOLDER -> deleteNoteFolder(args)
        NoteToolDefinitions.GET_ALL_NOTE_FOLDERS -> getAllNoteFolders()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun searchNotes(args: Map<String, Any?>): ToolExecutionResult {
        val query = args["query"] as? String ?: ""
        val r = SearchNotesResult(searchNotesByName(query))
        return ToolExecutionResult.success(r, json.encodeToString(SearchNotesResult.serializer(), r))
    }

    private suspend fun createNote(args: Map<String, Any?>): ToolExecutionResult {
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val content = args["content"] as? String ?: return ToolExecutionResult.error("Missing 'content'")
        val folderId = args["folderId"] as? String
        val pinned = args["pinned"] as? Boolean ?: false
        if (folderId != null) {
            runCatching { getNoteFolder(folderId) }.getOrNull()
                ?: return ToolExecutionResult.error("No folder found with ID: '$folderId'. The note was not created.")
        }
        val note = Note(title = title, content = content, folderId = folderId, pinned = pinned, createdDate = nowMillis(), updatedDate = nowMillis())
        val r = NoteIdResult(createdNoteId = upsertNote(note))
        return ToolExecutionResult.success(r, json.encodeToString(NoteIdResult.serializer(), r))
    }

    private suspend fun createMultipleNotes(args: Map<String, Any?>): ToolExecutionResult {
        val notesJson = args["notes"] as? String ?: return ToolExecutionResult.error("Missing 'notes'")
        val notes = try { json.decodeFromString<List<NoteInput>>(notesJson) } catch (e: Exception) { return ToolExecutionResult.error("Failed to parse notes: ${e.message}") }
        notes.forEach { input ->
            if (input.folderId != null) {
                runCatching { getNoteFolder(input.folderId) }.getOrNull()
                    ?: return ToolExecutionResult.error("No folder found with ID: '${input.folderId}'. The notes were not created.")
            }
        }
        val noteModels = notes.map { input ->
            Note(title = input.title, content = input.content, folderId = input.folderId, pinned = input.pinned, createdDate = nowMillis(), updatedDate = nowMillis())
        }
        val r = NoteIdsResult(createdNoteIds = upsertNotes(noteModels))
        return ToolExecutionResult.success(r, json.encodeToString(NoteIdsResult.serializer(), r))
    }

    private suspend fun getNoteById(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val note = getNote(id) ?: return ToolExecutionResult.error("No note found with ID: '$id'.")
        val r = NoteResult(note)
        return ToolExecutionResult.success(r, json.encodeToString(NoteResult.serializer(), r))
    }

    private suspend fun searchFolders(args: Map<String, Any?>): ToolExecutionResult {
        val name = args["name"] as? String ?: ""
        val r = SearchNoteFoldersResult(searchNoteFoldersByName(name))
        return ToolExecutionResult.success(r, json.encodeToString(SearchNoteFoldersResult.serializer(), r))
    }

    private suspend fun createFolder(args: Map<String, Any?>): ToolExecutionResult {
        val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val r = CreateNoteFolderResult(folderId = createFolderUseCase(folderName = name))
        return ToolExecutionResult.success(r, json.encodeToString(CreateNoteFolderResult.serializer(), r))
    }

    private suspend fun updateNote(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val title = args["title"] as? String
        val content = args["content"] as? String
        val folderId = args["folderId"] as? String
        val pinned = args["pinned"] as? Boolean
        val existing = getNote(id) ?: return ToolExecutionResult.error("No note found with ID: '$id'.")
        val updated = existing.copy(
            title = title ?: existing.title,
            content = content ?: existing.content,
            folderId = if (folderId != null) { if (folderId.isEmpty()) null else folderId } else existing.folderId,
            pinned = pinned ?: existing.pinned,
            updatedDate = nowMillis()
        )
        upsertNote(updated)
        val r = NoteResult(updated)
        return ToolExecutionResult.success(r, json.encodeToString(NoteResult.serializer(), r))
    }

    private suspend fun deleteNote(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val note = getNote(id) ?: return ToolExecutionResult.error("No note found with ID: '$id'.")
        deleteNoteUseCase(note)
        val r = DeleteNoteResult(deletedNoteId = id)
        return ToolExecutionResult.success(r, json.encodeToString(DeleteNoteResult.serializer(), r))
    }

    private suspend fun getAllNotes(): ToolExecutionResult {
        val r = SearchNotesResult(searchNotesByName(""))
        return ToolExecutionResult.success(r, json.encodeToString(SearchNotesResult.serializer(), r))
    }

    private suspend fun getNotesByFolder(args: Map<String, Any?>): ToolExecutionResult {
        val folderId = args["folderId"] as? String ?: return ToolExecutionResult.error("Missing 'folderId'")
        val allNotes = searchNotesByName("")
        val r = SearchNotesResult(allNotes.filter { it.folderId == folderId })
        return ToolExecutionResult.success(r, json.encodeToString(SearchNotesResult.serializer(), r))
    }

    private suspend fun updateNoteFolder(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val name = args["name"] as? String ?: return ToolExecutionResult.error("Missing 'name'")
        val allFolders = getAllNoteFoldersUseCase().first()
        val folder = allFolders.find { it.id == id }
            ?: allFolders.find { it.name.equals(id, ignoreCase = true) }
            ?: runCatching { getNoteFolder(id) }.getOrNull()
            ?: return ToolExecutionResult.error("No folder found with ID: '$id'. Use getAllNoteFolders to see valid folder IDs.")
        val updated = folder.copy(name = name)
        updateNoteFolderUseCase(updated)
        val r = NoteFolderResult(updated)
        return ToolExecutionResult.success(r, json.encodeToString(NoteFolderResult.serializer(), r))
    }

    private suspend fun deleteNoteFolder(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val allFolders = getAllNoteFoldersUseCase().first()
        val folder = allFolders.find { it.id == id }
            ?: allFolders.find { it.name.equals(id, ignoreCase = true) }
            ?: runCatching { getNoteFolder(id) }.getOrNull()
            ?: return ToolExecutionResult.error("No folder found with ID: '$id'. Use getAllNoteFolders to see valid folder IDs.")
        deleteNoteFolderUseCase(folder)
        val r = DeleteNoteFolderResult(deletedFolderId = folder.id)
        return ToolExecutionResult.success(r, json.encodeToString(DeleteNoteFolderResult.serializer(), r))
    }

    private suspend fun getAllNoteFolders(): ToolExecutionResult {
        val r = GetAllNoteFoldersResult(getAllNoteFoldersUseCase().first())
        return ToolExecutionResult.success(r, json.encodeToString(GetAllNoteFoldersResult.serializer(), r))
    }
}