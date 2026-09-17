package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object NoteToolDefinitions : ToolSetRegistration {
    const val SEARCH_NOTES = "searchNotes"
    const val CREATE_NOTE = "createNote"
    const val CREATE_MULTIPLE_NOTES = "createMultipleNotes"
    const val GET_NOTE_BY_ID = "getNoteById"
    const val SEARCH_NOTE_FOLDERS = "searchFolders"
    const val CREATE_NOTE_FOLDER = "createFolder"
    const val UPDATE_NOTE = "updateNote"
    const val DELETE_NOTE = "deleteNote"
    const val GET_ALL_NOTES = "getAllNotes"
    const val GET_NOTES_BY_FOLDER = "getNotesByFolder"
    const val UPDATE_NOTE_FOLDER = "updateNoteFolder"
    const val DELETE_NOTE_FOLDER = "deleteNoteFolder"
    const val GET_ALL_NOTE_FOLDERS = "getAllNoteFolders"

    override val definitions = listOf(
        ToolDefinition(name = SEARCH_NOTES, description = "Search notes by title/content (partial match, content truncated to 100 chars). If the query is empty, returns all notes.", category = "notes", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = CREATE_NOTE, description = "Create a note. Returns ID.", category = "notes", parameters = listOf(ToolParameter("title", ToolParameterType.String, true, "Note title"), ToolParameter("content", ToolParameterType.String, true, "Note content"), ToolParameter("folderId", ToolParameterType.String, false, "Optional Folder ID. If null, the note will be in the root folder. Use searchFolders to find an ID."), ToolParameter("pinned", ToolParameterType.Boolean, false, "Whether the note is pinned")), permissions = emptyList()),
        ToolDefinition(name = CREATE_MULTIPLE_NOTES, description = "Create multiple notes. Returns IDs.", category = "notes", parameters = listOf(ToolParameter("notes", ToolParameterType.String, true, "JSON array of NoteInput objects: [{title, content, folderId, pinned}]")), permissions = emptyList()),
        ToolDefinition(name = GET_NOTE_BY_ID, description = "Get full note by ID.", category = "notes", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Note ID")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_NOTE_FOLDERS, description = "Search folders by name (partial match). Returns folder IDs.", category = "notes", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Folder name query")), permissions = emptyList()),
        ToolDefinition(name = CREATE_NOTE_FOLDER, description = "Create a note folder. Returns ID.", category = "notes", parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "Folder name")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_NOTE, description = "Update an existing note. Only the fields you provide will be changed. Returns the updated note.", category = "notes", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Note ID"), ToolParameter("title", ToolParameterType.String, false, "New title (null to keep current)"), ToolParameter("content", ToolParameterType.String, false, "New content (null to keep current)"), ToolParameter("folderId", ToolParameterType.String, false, "New folder ID (null to keep current, empty string to move to root)"), ToolParameter("pinned", ToolParameterType.Boolean, false, "Whether the note is pinned")), permissions = emptyList()),
        ToolDefinition(name = DELETE_NOTE, description = "Delete a note permanently. This cannot be undone.", category = "notes", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "The ID of the note to delete")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_NOTES, description = "Get all notes. Returns the complete list of notes.", category = "notes", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_NOTES_BY_FOLDER, description = "Get all notes in a specific folder.", category = "notes", parameters = listOf(ToolParameter("folderId", ToolParameterType.String, true, "The folder ID to get notes for")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_NOTE_FOLDER, description = "Update a note folder's name. Use getAllNoteFolders first to find the folder ID.", category = "notes", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "The folder ID to update"), ToolParameter("name", ToolParameterType.String, true, "The new name for the folder")), permissions = emptyList()),
        ToolDefinition(name = DELETE_NOTE_FOLDER, description = "Delete a note folder permanently. Notes in this folder will be moved to root. Use getAllNoteFolders first to find the folder ID.", category = "notes", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "The folder ID to delete")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_NOTE_FOLDERS, description = "Get all note folders. Returns the complete list of folders with their IDs.", category = "notes", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = NoteToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}