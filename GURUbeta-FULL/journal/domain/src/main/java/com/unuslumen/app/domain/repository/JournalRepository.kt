// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.JournalEntry
import kotlinx.coroutines.flow.Flow

interface JournalRepository {

    fun getAllEntries(): Flow<List<JournalEntry>>

    suspend fun getEntry(id: String): JournalEntry?

    suspend fun searchEntries(title: String): List<JournalEntry>

    suspend fun addEntry(journal: JournalEntry)

    suspend fun updateEntry(journal: JournalEntry)

    suspend fun deleteEntry(journal: JournalEntry)
}