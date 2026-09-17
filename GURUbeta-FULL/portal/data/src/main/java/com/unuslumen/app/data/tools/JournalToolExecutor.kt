package com.unuslumen.app.data.tools

import com.unuslumen.app.data.nowMillis
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.domain.model.Mood
import com.unuslumen.app.domain.use_case.AddJournalEntryUseCase
import com.unuslumen.app.domain.use_case.DeleteJournalEntryUseCase
import com.unuslumen.app.domain.use_case.GetAllJournalEntriesUseCase
import com.unuslumen.app.domain.use_case.GetJournalEntryUseCase
import com.unuslumen.app.domain.use_case.SearchJournalEntriesUseCase
import com.unuslumen.app.domain.use_case.UpdateJournalEntryUseCase
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class JournalToolExecutor(
    private val addJournalEntry: AddJournalEntryUseCase,
    private val searchEntries: SearchJournalEntriesUseCase,
    private val getJournalEntry: GetJournalEntryUseCase,
    private val updateJournalEntryUseCase: UpdateJournalEntryUseCase,
    private val deleteJournalEntryUseCase: DeleteJournalEntryUseCase,
    private val getAllEntriesUseCase: GetAllJournalEntriesUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        JournalToolDefinitions.CREATE_JOURNAL_ENTRY -> createJournalEntry(args)
        JournalToolDefinitions.SEARCH_JOURNAL_ENTRIES -> { val r = SearchJournalEntriesResult(searchEntries(args["query"] as? String ?: "")); ToolExecutionResult.success(r, json.encodeToString(SearchJournalEntriesResult.serializer(), r)) }
        JournalToolDefinitions.GET_JOURNAL_ENTRY -> { val r = JournalEntryResult(getJournalEntry.invoke(args["id"] as? String ?: "")); ToolExecutionResult.success(r, json.encodeToString(JournalEntryResult.serializer(), r)) }
        JournalToolDefinitions.UPDATE_JOURNAL_ENTRY -> updateJournalEntry(args)
        JournalToolDefinitions.DELETE_JOURNAL_ENTRY -> deleteJournalEntry(args)
        JournalToolDefinitions.GET_ALL_JOURNAL_ENTRIES -> { val e = getAllEntriesUseCase(Order.DateModified(OrderType.DESC)).first(); val r = SearchJournalEntriesResult(e); ToolExecutionResult.success(r, json.encodeToString(SearchJournalEntriesResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createJournalEntry(args: Map<String, Any?>): ToolExecutionResult {
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val content = args["content"] as? String ?: return ToolExecutionResult.error("Missing 'content'")
        val moodStr = args["mood"] as? String ?: return ToolExecutionResult.error("Missing 'mood'")
        val mood = try { Mood.valueOf(moodStr.uppercase()) } catch (e: Exception) { return ToolExecutionResult.error("Invalid mood: $moodStr") }
        val id = Uuid.random().toString()
        val entry = JournalEntry(title = title, content = content, createdDate = nowMillis(), updatedDate = nowMillis(), mood = mood, id = id)
        addJournalEntry(entry)
        val r = JournalEntryIdResult(id); return ToolExecutionResult.success(r, json.encodeToString(JournalEntryIdResult.serializer(), r))
    }

    private suspend fun updateJournalEntry(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val existing = getJournalEntry.invoke(id) ?: return ToolExecutionResult.error("No journal entry found with ID: '$id'")
        val mood = (args["mood"] as? String)?.let { try { Mood.valueOf(it.uppercase()) } catch (e: Exception) { null } } ?: existing.mood
        val updated = existing.copy(title = (args["title"] as? String) ?: existing.title, content = (args["content"] as? String) ?: existing.content, mood = mood, updatedDate = nowMillis())
        updateJournalEntryUseCase(updated)
        val r = JournalEntryResult(updated); return ToolExecutionResult.success(r, json.encodeToString(JournalEntryResult.serializer(), r))
    }

    private suspend fun deleteJournalEntry(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val entry = getJournalEntry.invoke(id) ?: return ToolExecutionResult.error("No journal entry found with ID: '$id'")
        deleteJournalEntryUseCase(entry)
        val r = DeleteJournalEntryResult(id); return ToolExecutionResult.success(r, json.encodeToString(DeleteJournalEntryResult.serializer(), r))
    }
}