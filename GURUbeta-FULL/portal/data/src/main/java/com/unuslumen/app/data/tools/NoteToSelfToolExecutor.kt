package com.unuslumen.app.data.tools

import com.unuslumen.app.database.dao.GuruNoteToSelfDao
import com.unuslumen.app.database.entity.GuruNoteToSelfEntity
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Factory
class NoteToSelfToolExecutor(
    private val dao: GuruNoteToSelfDao
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        NoteToSelfToolDefinitions.CREATE_NOTE_TO_SELF -> createNote(args)
        NoteToSelfToolDefinitions.LIST_NOTES_TO_SELF -> listNotes()
        NoteToSelfToolDefinitions.UPDATE_NOTE_TO_SELF -> updateNote(args)
        NoteToSelfToolDefinitions.DELETE_NOTE_TO_SELF -> deleteNote(args)
        NoteToSelfToolDefinitions.ENABLE_NOTE_TO_SELF -> setEnabled(args, true)
        NoteToSelfToolDefinitions.DISABLE_NOTE_TO_SELF -> setEnabled(args, false)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createNote(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val name = (args["name"] as? String)?.trim().orEmpty()
        val content = (args["content"] as? String)?.trim().orEmpty()
        val keywords = (args["keywords"] as? String)?.trim().orEmpty()
        if (name.isBlank()) return@withContext ToolExecutionResult.error("Missing 'name'")
        if (content.isBlank()) return@withContext ToolExecutionResult.error("Missing 'content'")

        val sanitizedName = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        if (sanitizedName.isBlank()) return@withContext ToolExecutionResult.error("Name sanitises to nothing, use letters/numbers/underscores")

        val now = System.currentTimeMillis()
        val existing = dao.getByName(sanitizedName)
        val note = if (existing != null) {
            // Same-name upsert: content/keywords refresh, stats preserved.
            existing.copy(content = content, triggerKeywords = keywords, updatedAt = now)
        } else {
            GuruNoteToSelfEntity(
                id = Uuid.random().toString(),
                name = sanitizedName,
                content = content,
                triggerKeywords = keywords,
                enabled = true,
                source = "guru",
                createdAt = now,
                updatedAt = now
            )
        }
        dao.insert(note)
        val r = NoteToSelfActionResult(sanitizedName, "Saved. Fires when the user's message matches: ${keywords.ifBlank { "(none - never auto-fires)" }}")
        ToolExecutionResult.success(r, json.encodeToString(NoteToSelfActionResult.serializer(), r))
    }

    private suspend fun listNotes(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val notes = dao.getAll()
        val items = notes.map {
            NoteToSelfInfo(
                id = it.id, name = it.name, keywords = it.triggerKeywords,
                enabled = it.enabled, source = it.source,
                fireCount = it.fireCount, lastFiredAt = it.lastFiredAt,
                updatedAt = it.updatedAt, content = it.content.take(300)
            )
        }
        val r = NoteToSelfListResult(items)
        ToolExecutionResult.success(r, json.encodeToString(NoteToSelfListResult.serializer(), r))
    }

    private suspend fun updateNote(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val key = (args["name"] as? String)?.trim().orEmpty()
        if (key.isBlank()) return@withContext ToolExecutionResult.error("Missing 'name'")
        val note = dao.getByName(key) ?: dao.getById(key)
            ?: return@withContext ToolExecutionResult.error("Note not found: $key")

        val newName = (args["newName"] as? String)?.trim()?.lowercase()?.replace(Regex("[^a-z0-9_]"), "_")
        val newContent = (args["content"] as? String)?.trim()
        val newKeywords = (args["keywords"] as? String)?.trim()

        val updated = note.copy(
            name = if (!newName.isNullOrBlank()) newName else note.name,
            content = if (!newContent.isNullOrBlank()) newContent else note.content,
            triggerKeywords = if (newKeywordsChanged(newKeywords)) newKeywords.orEmpty() else note.triggerKeywords,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)
        val r = NoteToSelfActionResult(updated.name, "Updated.")
        ToolExecutionResult.success(r, json.encodeToString(NoteToSelfActionResult.serializer(), r))
    }

    private fun newKeywordsChanged(newKeywords: String?): Boolean = newKeywords != null

    private suspend fun deleteNote(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val key = (args["name"] as? String)?.trim().orEmpty()
        if (key.isBlank()) return@withContext ToolExecutionResult.error("Missing 'name'")
        val note = dao.getByName(key) ?: dao.getById(key)
            ?: return@withContext ToolExecutionResult.error("Note not found: $key")
        dao.deleteById(note.id)
        val r = NoteToSelfActionResult(note.name, "Deleted.")
        ToolExecutionResult.success(r, json.encodeToString(NoteToSelfActionResult.serializer(), r))
    }

    private suspend fun setEnabled(args: Map<String, Any?>, enabled: Boolean): ToolExecutionResult = withContext(Dispatchers.IO) {
        val key = (args["name"] as? String)?.trim().orEmpty()
        if (key.isBlank()) return@withContext ToolExecutionResult.error("Missing 'name'")
        val note = dao.getByName(key) ?: dao.getById(key)
            ?: return@withContext ToolExecutionResult.error("Note not found: $key")
        dao.setEnabled(note.id, enabled, System.currentTimeMillis())
        val r = NoteToSelfActionResult(note.name, if (enabled) "Enabled." else "Disabled.")
        ToolExecutionResult.success(r, json.encodeToString(NoteToSelfActionResult.serializer(), r))
    }
}

@Serializable
data class NoteToSelfInfo(
    val id: String,
    val name: String,
    val keywords: String,
    val enabled: Boolean,
    val source: String,
    val fireCount: Int,
    val lastFiredAt: Long,
    val updatedAt: Long,
    val content: String
) : com.unuslumen.app.data.tools.registry.ToolResultData

@Serializable
data class NoteToSelfActionResult(val name: String, val status: String) : com.unuslumen.app.data.tools.registry.ToolResultData

@Serializable
data class NoteToSelfListResult(val notes: List<NoteToSelfInfo>) : com.unuslumen.app.data.tools.registry.ToolResultData