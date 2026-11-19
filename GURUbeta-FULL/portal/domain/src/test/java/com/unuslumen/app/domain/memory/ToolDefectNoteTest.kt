// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recallable record of a broken tool and the way round it (issue 466).
 *
 * THE GAP. There was no category for a defect, a broken tool or a workaround, so an
 * agent that discovered "tool X cannot reach device Y, use protocol Z directly" had
 * three bad options: store it as `knowledge`, where it competes with every other
 * fact and is retrieved by similarity rather than relevance; put it in a note to
 * self, keyed on what the human will say, so it never fires when the agent hits the
 * bug again; or leave it in the conversation, where compaction eats it.
 *
 * Workarounds are the most frequently reused knowledge an agent has, so the loop
 * "every mistake is a signal, every correction is a permanent upgrade" had nowhere
 * to write the upgrade down.
 */
class ToolDefectNoteTest {

    // ------------------------------------------------------------------
    // The categories exist, and they are additions the prompt permits.
    // ------------------------------------------------------------------

    @Test
    fun tool_defect_and_workaround_are_permitted_categories() {
        assertTrue(
            "'tool_defect' must be a real category, not a word an agent guesses",
            MemoryCategories.normalise("tool_defect") is MemoryCategories.Normalisation.Canonical,
        )
        assertTrue(
            MemoryCategories.normalise("workaround") is MemoryCategories.Normalisation.Canonical,
        )
    }

    @Test
    fun adding_them_did_not_displace_his_own_seventeen() {
        // The prompt's own names must all still resolve. An addition that broke one
        // of them would be a much worse trade than the gap it filled.
        listOf(
            "user_preference", "personal_info", "project_info", "decision",
            "pattern", "relationship", "knowledge",
            "guru_identity", "guru_preference", "guru_experience", "guru_belief",
            "shared_history", "temporary_state", "correction", "lesson",
            "capability", "commitment",
        ).forEach { name ->
            assertTrue(
                "$name must still be canonical",
                MemoryCategories.normalise(name) is MemoryCategories.Normalisation.Canonical,
            )
        }
        assertEquals(19, MemoryCategories.ALL.size)
    }

    @Test
    fun the_separator_and_case_variants_of_a_defect_still_resolve() {
        // The same repair every other category gets, and the one an agent will
        // actually type under pressure: "tool-defect", "ToolDefect".
        listOf("tool-defect", "tooldefect", "Tool_Defect", "work-arounds").forEach { spelling ->
            val resolved = MemoryCategories.normalise(spelling)
            assertTrue(
                "$spelling must resolve rather than be refused: got $resolved",
                resolved is MemoryCategories.Normalisation.Canonical ||
                    resolved is MemoryCategories.Normalisation.Repaired,
            )
        }
    }

    // ------------------------------------------------------------------
    // The record carries the field that makes it expire.
    // ------------------------------------------------------------------

    @Test
    fun the_rendered_text_names_the_tool_the_symptom_the_expectation_and_the_build() {
        val note = ToolDefectNote(
            toolName = "netDial",
            observed = "it reported no results against a set-top box that answers DIAL",
            expected = "it should launch the app on the first attempt",
            workaround = "send the DIAL request directly and treat HTTP 201 as the only success",
            observedOnBuild = "3.77 (101)",
        )
        val text = note.toFactText()

        assertTrue("the tool must be named: $text", text.contains("netDial"))
        assertTrue("the symptom must be there: $text", text.contains("reported no results"))
        assertTrue("the expectation must be there: $text", text.contains("launch the app"))
        assertTrue("the workaround must be there: $text", text.contains("DIAL request directly"))
        assertTrue(
            "THE BUILD IS WHAT LETS A STALE WORKAROUND BE SPOTTED: $text",
            text.contains("3.77 (101)"),
        )
    }

    @Test
    fun a_defect_recorded_before_a_workaround_exists_is_still_worth_storing() {
        // A defect with no workaround yet is still a fact worth remembering: it stops
        // the agent concluding the tool is broken in a new way every session.
        val note = ToolDefectNote(
            toolName = "tvPress",
            observed = "key presses reached the wrong input",
            expected = "the press should go to the active input",
            observedOnBuild = "3.80 (104)",
        )
        assertFalse(note.hasWorkaround)
        val text = note.toFactText()
        assertFalse("no empty workaround sentence: $text", text.contains("What works for now"))
        assertTrue(text.contains("3.80 (104)"))
    }

    @Test
    fun the_text_reads_as_a_sentence_not_a_machine_blob() {
        // The brain's write gate refuses raw JSON blobs, and a defect record that
        // refused at the gate would be the same silent nothing as before.
        val text = ToolDefectNote(
            toolName = "sqlQuery",
            observed = "a bare database name was refused",
            expected = "the app's own database should resolve by name",
            workaround = "pass the absolute path",
            observedOnBuild = "3.80 (104)",
        ).toFactText()

        assertFalse("must not start as JSON: $text", text.trim().startsWith("{"))
        assertFalse(text.trim().startsWith("["))
        assertTrue("must be prose with a full stop: $text", text.trim().endsWith("."))
    }

    @Test
    fun a_trailing_full_stop_in_the_expectation_does_not_double_up() {
        val text = ToolDefectNote(
            toolName = "x",
            observed = "y",
            expected = "do the right thing.",
            observedOnBuild = "3.80 (104)",
        ).toFactText()
        assertFalse("no double full stop: $text", text.contains(".."))
    }
}
