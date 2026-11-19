// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.masks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mask catalogue, readable by a model (issue 491).
 *
 * THE DEFECT. GURU had a mask feature with a real selector and no tool a model could
 * call: of 607 tool names the app defined, zero contained "mask", while the Self
 * Improvement Engine's prompt twice names masks as a capability it must be able to
 * develop and instructs the agent to follow the existing mask architecture rather than
 * inventing a parallel concept. It could not see the architecture at all.
 *
 * WHAT A MASK ACTUALLY IS, which the ticket asked before shaping the tools: a
 * four-member enum rendered by `GuruMaskSelector`, with the selection held in the portal
 * screen's own state and never persisted. Not a stored record, so there is nothing an
 * authoring verb could write. The tools read, and say so.
 */
class GuruMasksTest {

    @Test
    fun the_catalogue_holds_the_four_masks_the_selector_renders() {
        assertEquals(
            listOf("Coder", "Grafter", "Wingman", "Slik"),
            GuruMasks.names(),
        )
    }

    @Test
    fun exactly_one_mask_is_the_default_and_it_is_coder() {
        // The portal opens with Coder selected (rememberSaveable in PortalScreen). Two
        // defaults would be an ambiguity, and none would mean the selector opens with
        // nothing chosen.
        assertEquals(1, GuruMasks.ALL.count { it.isDefault })
        assertEquals("Coder", GuruMasks.default().name)
    }

    @Test
    fun every_mask_says_what_it_is_for() {
        // THE POINT OF THE WHOLE CHANGE. The enum carried a display name and nothing
        // else, so a model could learn the word "Grafter" and not what it means. A
        // purpose is what lets it decide whether a mask fits a task.
        GuruMasks.ALL.forEach { mask ->
            assertTrue(
                "${mask.name} must say what it is for",
                mask.purpose.length > 20,
            )
        }
    }

    @Test
    fun a_mask_can_be_read_by_name_in_either_case() {
        // A caller reaching for "coder" means the Coder mask; refusing a lowercase
        // spelling would teach the wrong lesson.
        assertNotNull(GuruMasks.byName("Coder"))
        assertNotNull(GuruMasks.byName("coder"))
        assertNotNull(GuruMasks.byName("  GRAFTER  "))
    }

    @Test
    fun an_unknown_name_resolves_to_null_rather_than_a_guess() {
        // Null lets a caller be told the real catalogue. A nearest-match guess would put
        // a wrong mask in front of the owner and call it a success.
        assertNull(GuruMasks.byName("Nonexistent"))
        assertNull(GuruMasks.byName(""))
        assertNull(GuruMasks.byName(null))
    }

    @Test
    fun the_names_are_unique_so_a_lookup_cannot_be_ambiguous() {
        assertEquals(GuruMasks.ALL.size, GuruMasks.names().distinct().size)
    }
}
