package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object JournalToolDefinitions : ToolSetRegistration {
    const val CREATE_JOURNAL_ENTRY = "createJournalEntry"
    const val SEARCH_JOURNAL_ENTRIES = "searchJournalEntries"
    const val GET_JOURNAL_ENTRY = "getJournalEntry"
    const val UPDATE_JOURNAL_ENTRY = "updateJournalEntry"
    const val DELETE_JOURNAL_ENTRY = "deleteJournalEntry"
    const val GET_ALL_JOURNAL_ENTRIES = "getAllJournalEntries"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_JOURNAL_ENTRY, description = "Create journal entry. Returns ID.", category = "journal", parameters = listOf(ToolParameter("title", ToolParameterType.String, true, "Entry title"), ToolParameter("content", ToolParameterType.String, true, "Entry content"), ToolParameter("mood", ToolParameterType.String, true, "Mood. Options: HAPPY, GOOD, NEUTRAL, SAD, ANGRY, ANXIOUS, EXCITED, TIRED, GRATEFUL")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_JOURNAL_ENTRIES, description = "Search journal entries by title/content (partial match, content truncated to 100 chars).", category = "journal", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = GET_JOURNAL_ENTRY, description = "Get journal entry by ID.", category = "journal", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Entry ID")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_JOURNAL_ENTRY, description = "Update an existing journal entry. Only the fields you provide will be changed. Returns the updated entry.", category = "journal", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Entry ID"), ToolParameter("title", ToolParameterType.String, false, "New title"), ToolParameter("content", ToolParameterType.String, false, "New content"), ToolParameter("mood", ToolParameterType.String, false, "New mood")), permissions = emptyList()),
        ToolDefinition(name = DELETE_JOURNAL_ENTRY, description = "Delete a journal entry permanently. This cannot be undone.", category = "journal", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Entry ID")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_JOURNAL_ENTRIES, description = "Get all journal entries. Returns the complete list.", category = "journal", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = JournalToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}