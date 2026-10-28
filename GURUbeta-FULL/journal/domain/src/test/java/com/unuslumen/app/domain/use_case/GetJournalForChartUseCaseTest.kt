// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.domain.model.Mood
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart reads every journal entry once, filters it with the caller's
 * selector, and sorts ascending by creation date. A chart drawn out of order
 * or off a filtered feed looks wrong in ways that are hard to trace, so the
 * contract is pinned here.
 */
class GetJournalForChartUseCaseTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `entries come back sorted oldest first`() = runTest(dispatcher) {
        val entries = listOf(
            entry(id = "c", createdDate = 3_000L),
            entry(id = "a", createdDate = 1_000L),
            entry(id = "b", createdDate = 2_000L),
        )
        val result = useCase(entries)({ true })
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun `the caller selector filters the feed`() = runTest(dispatcher) {
        val entries = listOf(
            entry(id = "happy", createdDate = 1_000L, mood = Mood.AWESOME),
            entry(id = "sad", createdDate = 2_000L, mood = Mood.TERRIBLE),
        )
        val result = useCase(entries)({ it.mood == Mood.TERRIBLE })
        assertEquals(listOf("sad"), result.map { it.id })
    }

    @Test
    fun `an empty journal yields an empty chart`() = runTest(dispatcher) {
        val result = useCase(emptyList())({ true })
        assertTrue(result.isEmpty())
    }

    @Test
    fun `entries with equal dates keep a stable relative order`() = runTest(dispatcher) {
        val entries = listOf(
            entry(id = "first", createdDate = 5_000L),
            entry(id = "second", createdDate = 5_000L),
        )
        val result = useCase(entries)({ true })
        assertEquals(listOf("first", "second"), result.map { it.id })
    }

    private fun useCase(entries: List<JournalEntry>) = GetJournalForChartUseCase(
        journalRepository = FakeJournalRepository(entries),
        defaultDispatcher = dispatcher,
    )

    private fun entry(
        id: String,
        createdDate: Long,
        mood: Mood = Mood.OKAY,
    ) = JournalEntry(
        title = "Entry $id",
        content = "",
        createdDate = createdDate,
        updatedDate = createdDate,
        mood = mood,
        id = id,
    )
}

/**
 * In-memory JournalRepository. Not a mock: the chart tests need a real Flow
 * emitting real entries so ordering and filtering are proven end to end.
 */
private class FakeJournalRepository(
    private val entries: List<JournalEntry>,
) : com.unuslumen.app.domain.repository.JournalRepository {

    override fun getAllEntries(): Flow<List<JournalEntry>> = flowOf(entries)

    override suspend fun getEntry(id: String): JournalEntry? = entries.find { it.id == id }

    override suspend fun searchEntries(title: String): List<JournalEntry> =
        entries.filter { it.title.contains(title, ignoreCase = true) }

    override suspend fun addEntry(journal: JournalEntry) = Unit

    override suspend fun updateEntry(journal: JournalEntry) = Unit

    override suspend fun deleteEntry(journal: JournalEntry) = Unit
}
