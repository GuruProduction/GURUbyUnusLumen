// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.JournalEntry
import kotlinx.serialization.Serializable

@Serializable data class JournalEntryIdResult(val createdJournalEntryId: String) : ToolResultData
@Serializable data class SearchJournalEntriesResult(val entries: List<JournalEntry>) : ToolResultData
@Serializable data class JournalEntryResult(val entry: JournalEntry?) : ToolResultData
@Serializable data class DeleteJournalEntryResult(val deletedEntryId: String) : ToolResultData