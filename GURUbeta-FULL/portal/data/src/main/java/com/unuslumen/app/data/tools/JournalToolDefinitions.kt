// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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

    /**
     * The moods a journal entry can carry, read from the enum that decides.
     *
     * THE LIST USED TO BE TYPED OUT BY HAND AND WAS WRONG. It advertised
     * HAPPY, GOOD, NEUTRAL, SAD, ANGRY, ANXIOUS, EXCITED, TIRED and GRATEFUL,
     * while `Mood.kt` declares AWESOME, GOOD, OKAY, BAD and TERRIBLE. Eight of
     * the nine advertised values were rejected by the executor, so a model
     * following the description produced `{"error":"Invalid mood: EXCITED"}` and
     * the user saw a journal entry that was never created.
     *
     * Deriving it from [Mood] makes the description and the validation one fact
     * rather than two copies of one fact. A mood added to the enum appears in
     * the description with no edit here, and a mood removed from it disappears,
     * so the drift that caused the bug has nowhere to happen.
     */
    private val moodOptions: String =
        com.unuslumen.app.domain.model.Mood.entries.joinToString(", ") { it.name }

    private val moodParameterDescription: String =
        "Mood. Options: $moodOptions"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_JOURNAL_ENTRY, description = "Create journal entry. Returns ID.", category = "journal", parameters = listOf(ToolParameter("title", ToolParameterType.String, true, "Entry title"), ToolParameter("content", ToolParameterType.String, true, "Entry content"), ToolParameter("mood", ToolParameterType.String, true, moodParameterDescription)), permissions = emptyList()),
        ToolDefinition(name = SEARCH_JOURNAL_ENTRIES, description = "Search journal entries by title/content (partial match, content truncated to 100 chars).", category = "journal", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = GET_JOURNAL_ENTRY, description = "Get journal entry by ID.", category = "journal", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Entry ID")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_JOURNAL_ENTRY, description = "Update an existing journal entry. Only the fields you provide will be changed. Returns the updated entry.", category = "journal", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Entry ID"), ToolParameter("title", ToolParameterType.String, false, "New title"), ToolParameter("content", ToolParameterType.String, false, "New content"), ToolParameter("mood", ToolParameterType.String, false, moodParameterDescription)), permissions = emptyList()),
        ToolDefinition(name = DELETE_JOURNAL_ENTRY, description = "Delete a journal entry permanently. This cannot be undone.", category = "journal", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Entry ID")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_JOURNAL_ENTRIES, description = "Get all journal entries. Returns the complete list.", category = "journal", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = JournalToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}