// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.domain.model.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JournalToolDefinitions mood list tests.
 *
 * The description advertised HAPPY, GOOD, NEUTRAL, SAD, ANGRY, ANXIOUS, EXCITED,
 * TIRED and GRATEFUL while the enum declared AWESOME, GOOD, OKAY, BAD and
 * TERRIBLE. Eight of the nine advertised values were rejected at execution, so a
 * model following the tool's own description produced failures. The user saw a
 * journal entry that was never created.
 *
 * The tests below assert the description and the enum are the same fact. Any
 * future divergence fails here rather than in the owner's journal.
 */
class JournalToolDefinitionsTest {

    private fun moodDescriptionFor(toolName: String): String {
        val definition = JournalToolDefinitions.definitions.first { it.name == toolName }
        return definition.parameters.first { it.name == "mood" }.description
    }

    @Test
    fun `every mood the enum declares is advertised`() {
        val description = moodDescriptionFor(JournalToolDefinitions.CREATE_JOURNAL_ENTRY)
        for (mood in Mood.entries) {
            assertTrue(
                "the description must advertise $mood or the model cannot use it",
                description.contains(mood.name),
            )
        }
    }

    @Test
    fun `no mood the enum lacks is advertised`() {
        val description = moodDescriptionFor(JournalToolDefinitions.CREATE_JOURNAL_ENTRY)
        // The exact values from the bug report, all eight of which were rejected.
        for (invented in listOf("HAPPY", "NEUTRAL", "SAD", "ANGRY", "ANXIOUS", "EXCITED", "TIRED", "GRATEFUL")) {
            assertFalse(
                "$invented is not a Mood and advertising it produces a rejected call",
                description.contains(invented),
            )
        }
    }

    @Test
    fun `the update tool advertises the same moods as the create tool`() {
        assertEquals(
            "both tools validate against one enum, so both must describe one list",
            moodDescriptionFor(JournalToolDefinitions.CREATE_JOURNAL_ENTRY),
            moodDescriptionFor(JournalToolDefinitions.UPDATE_JOURNAL_ENTRY),
        )
    }

    @Test
    fun `every advertised value parses back through the enum`() {
        val description = moodDescriptionFor(JournalToolDefinitions.CREATE_JOURNAL_ENTRY)
        val advertised = description.substringAfter("Options: ").split(", ").filter { it.isNotBlank() }

        assertTrue("the description must advertise at least one mood", advertised.isNotEmpty())
        for (value in advertised) {
            // This is the exact conversion the executor performs. If it throws,
            // the description is lying about what the tool accepts.
            Mood.valueOf(value.trim().uppercase())
        }
    }
}
