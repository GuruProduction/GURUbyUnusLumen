package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.JournalEntry
import kotlinx.serialization.Serializable

@Serializable data class JournalEntryIdResult(val createdJournalEntryId: String) : ToolResultData
@Serializable data class SearchJournalEntriesResult(val entries: List<JournalEntry>) : ToolResultData
@Serializable data class JournalEntryResult(val entry: JournalEntry?) : ToolResultData
@Serializable data class DeleteJournalEntryResult(val deletedEntryId: String) : ToolResultData