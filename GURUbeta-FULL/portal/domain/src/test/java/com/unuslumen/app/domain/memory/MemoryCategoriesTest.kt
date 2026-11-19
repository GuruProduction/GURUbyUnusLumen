// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory category vocabulary (issue 485).
 *
 * THE DEFECT. `addMemoryFact` took a category as free text with no enumeration and
 * no check, so the brain's categories were whatever a model happened to type.
 * `user_preference`, `user-preferences`, `preference`, `preferences` and
 * `Preferences` were five categories holding one kind of fact, and the category
 * filter could find one of them at a time. A filter over an open vocabulary is not
 * a filter.
 *
 * THE SET IS STEVEN'S, NOT MINE. Every canonical name below comes from section 6 of
 * his own Archivist prompt: seven it calls established, ten further ones it
 * permits. The app knew none of those names existed, so the prompt described a
 * vocabulary the storage layer did not have.
 */
class MemoryCategoriesTest {

    // ------------------------------------------------------------------
    // The set itself is his document's set.
    // ------------------------------------------------------------------

    @Test
    fun the_seven_established_categories_are_exactly_the_archivist_prompts_seven() {
        assertEquals(
            listOf(
                "user_preference", "personal_info", "project_info", "decision",
                "pattern", "relationship", "knowledge",
            ),
            MemoryCategories.ESTABLISHED,
        )
    }

    @Test
    fun the_archivist_prompts_ten_are_all_present_and_nothing_replaced_them() {
        // THE PROMPT CALLS THIS LIST OPEN: "use additional meaningful categories
        // where appropriate, such as". So the assertion is that his ten are all
        // still there, in his order, at the front — not that nothing else exists.
        // Issue 466 added two more to the list, which the prompt explicitly permits.
        val hisTen = listOf(
            "guru_identity", "guru_preference", "guru_experience", "guru_belief",
            "shared_history", "temporary_state", "correction", "lesson",
            "capability", "commitment",
        )
        assertEquals(hisTen, MemoryCategories.ADDITIONAL.take(hisTen.size))
        assertEquals(
            hisTen,
            MemoryCategories.ADDITIONAL.filter { it in hisTen },
        )
    }

    @Test
    fun the_whole_set_has_no_duplicates_and_the_established_seven_lead_it() {
        assertEquals(MemoryCategories.ALL.size, MemoryCategories.ALL.distinct().size)
        assertEquals(
            MemoryCategories.ESTABLISHED,
            MemoryCategories.ALL.take(MemoryCategories.ESTABLISHED.size),
        )
        assertTrue(
            "the set must be at least his seventeen plus whatever a ticket has added",
            MemoryCategories.ALL.size >= 17,
        )
    }

    // ------------------------------------------------------------------
    // Normalisation: the five spellings the issue measured.
    // ------------------------------------------------------------------

    @Test
    fun the_five_spellings_that_were_five_categories_become_one() {
        listOf("user_preference", "user-preferences", "preference", "preferences", "Preferences")
            .forEach { spelling ->
                val normalised = MemoryCategories.normalise(spelling)
                assertTrue(
                    "$spelling must normalise onto user_preference, got $normalised",
                    normalised is MemoryCategories.Normalisation.Canonical ||
                        normalised is MemoryCategories.Normalisation.Repaired,
                )
                val resolved = when (normalised) {
                    is MemoryCategories.Normalisation.Canonical -> normalised.category
                    is MemoryCategories.Normalisation.Repaired -> normalised.category
                    else -> ""
                }
                assertEquals("$spelling", "user_preference", resolved)
            }
    }

    @Test
    fun a_canonical_name_is_reported_as_canonical_not_as_a_repair() {
        assertTrue(MemoryCategories.normalise("decision") is MemoryCategories.Normalisation.Canonical)
    }

    @Test
    fun a_repair_keeps_what_was_written_so_the_call_can_say_so() {
        val repaired = MemoryCategories.normalise("Preferences") as MemoryCategories.Normalisation.Repaired
        assertEquals("user_preference", repaired.category)
        assertEquals("Preferences", repaired.asWritten)
    }

    @Test
    fun case_and_separator_variation_is_repaired_for_every_canonical_name() {
        MemoryCategories.ALL.forEach { name ->
            listOf(name.uppercase(), name.replace('_', '-')) .forEach { variant ->
                val result = MemoryCategories.normalise(variant)
                assertTrue(
                    "$variant must resolve onto $name, got $result",
                    result is MemoryCategories.Normalisation.Canonical ||
                        result is MemoryCategories.Normalisation.Repaired,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Refusals.
    // ------------------------------------------------------------------

    @Test
    fun an_unknown_category_is_refused_with_the_permitted_set_named() {
        // `tool_defect` WAS the example here until issue 466 added it as a real
        // category. The assertion now uses a word that is genuinely outside the set.
        val refused = MemoryCategories.normalise("astrology_reading") as MemoryCategories.Normalisation.Refused
        assertTrue("must name the offending value: ${refused.reason}", refused.reason.contains("astrology_reading"))
        assertTrue("must name user_preference: ${refused.reason}", refused.reason.contains("user_preference"))
        assertTrue("must name commitment: ${refused.reason}", refused.reason.contains("commitment"))
        assertTrue(
            "must name the categories added after the original set: ${refused.reason}",
            refused.reason.contains("tool_defect"),
        )
    }

    @Test
    fun an_empty_category_is_refused_rather_than_defaulted() {
        assertTrue(MemoryCategories.normalise(null) is MemoryCategories.Normalisation.Refused)
        assertTrue(MemoryCategories.normalise("   ") is MemoryCategories.Normalisation.Refused)
    }

    // ------------------------------------------------------------------
    // Existing rows: matched, not silently left.
    // ------------------------------------------------------------------

    @Test
    fun a_filter_finds_a_row_stored_under_a_legacy_spelling() {
        // Rows already in the brain were written before this vocabulary existed. A
        // repair that only applies to new writes leaves every existing memory
        // unfindable by category, which is the issue's fourth requirement.
        assertTrue(MemoryCategories.matches("user_preference", "preferences"))
        assertTrue(MemoryCategories.matches("user_preference", "Preferences"))
        assertTrue(MemoryCategories.matches("preferences", "user_preference"))
    }

    @Test
    fun a_filter_still_matches_an_exact_stored_name() {
        assertTrue(MemoryCategories.matches("decision", "decision"))
        assertFalse(MemoryCategories.matches("decision", "pattern"))
    }

    @Test
    fun a_null_filter_matches_everything_and_an_unknown_filter_matches_nothing() {
        assertTrue(MemoryCategories.matches(null, "anything at all"))
        assertFalse(
            "an unrecognised filter must not silently widen to the whole brain",
            MemoryCategories.matches("not_a_category", "decision"),
        )
    }

    @Test
    fun a_stored_row_whose_category_is_outside_the_set_still_matches_itself() {
        // Older rows may carry a category nobody permitted. A filter naming that
        // exact spelling must still find them rather than pretend they do not exist.
        assertTrue(MemoryCategories.matches("hive_marker", "hive_marker"))
    }
}
