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