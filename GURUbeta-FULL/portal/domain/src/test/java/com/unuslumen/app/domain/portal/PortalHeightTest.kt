// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The portal height rule (issue 477).
 *
 * The height a WebView is given used to be computed inline from
 * `body.scrollHeight` divided by the SCREEN's density, clamped to 2000. Three
 * things were wrong and none of them needed a device to catch:
 *
 *   - the wrong density, which made every auto-measured portal about 2.75 times
 *     too short on a 440dpi phone;
 *   - a single measurement at 100ms, so anything that arrived later was clipped;
 *   - a clamp that turned away content rather than letting it scroll.
 *
 * The measurement trigger is the injected script's job and is proven by reading
 * the document. What is proved here is the arithmetic and the policy.
 */
class PortalHeightTest {

    // ------------------------------------------------------------------
    // Unit conversion.
    // ------------------------------------------------------------------

    @Test
    fun css_pixels_are_divided_by_the_view_density_not_the_screen_density() {
        // 400 CSS px at 2.75 density is 146 dp. The old code produced the same
        // number by accident only when the view and screen densities matched,
        // which is exactly the case that hid the bug on an emulator.
        assertEquals(146, PortalHeight.cssPxToDp(400, 2.75f))
        assertEquals(400, PortalHeight.cssPxToDp(400, 1.0f))
        assertEquals(200, PortalHeight.cssPxToDp(400, 2.0f))
    }

    @Test
    fun a_zero_or_negative_report_never_collapses_the_box() {
        // The conversion reports "nothing to see" as zero, and the policy in
        // `next` is what refuses to act on it. Applied together, a document with
        // no height yet leaves the box exactly where it was.
        assertEquals(0, PortalHeight.cssPxToDp(0, 2.75f))
        assertEquals(0, PortalHeight.cssPxToDp(-10, 2.75f))
        assertEquals(300, PortalHeight.next(300, 0, 2.75f))
        assertEquals(PortalHeight.MIN_DP, PortalHeight.next(PortalHeight.MIN_DP, 2, 2.75f))
    }

    @Test
    fun a_zero_density_is_treated_as_one_rather_than_dividing_by_zero() {
        assertEquals(300, PortalHeight.cssPxToDp(300, 0f))
    }

    @Test
    fun the_result_is_rounded_up_so_a_fractional_line_is_never_clipped() {
        // 101 px at 2.75 is 36.7 dp. Rounding down would cut the last line.
        assertEquals(37, PortalHeight.cssPxToDp(101, 2.75f))
    }

    // ------------------------------------------------------------------
    // The clamp.
    // ------------------------------------------------------------------

    @Test
    fun a_short_document_still_gets_the_minimum() {
        assertEquals(PortalHeight.MIN_DP, PortalHeight.next(0, 4, 1f))
    }

    @Test
    fun a_runaway_document_is_capped_so_the_chat_survives() {
        val huge = PortalHeight.next(PortalHeight.MIN_DP, 900_000, 1f)
        assertEquals(PortalHeight.MAX_DP, huge)
        assertTrue(
            "the ceiling must let the portal scroll rather than push the conversation off screen",
            huge == PortalHeight.MAX_DP,
        )
    }

    // ------------------------------------------------------------------
    // Grow freely, shrink reluctantly.
    // ------------------------------------------------------------------

    @Test
    fun growth_is_always_believed() {
        assertEquals(500, PortalHeight.next(300, 500, 1f))
    }

    @Test
    fun a_small_shrink_is_ignored_so_animation_does_not_flicker() {
        // A reflow of 10 dp is a transient, not a removal.
        assertEquals(300, PortalHeight.next(300, 290, 1f))
    }

    @Test
    fun a_large_shrink_is_believed_because_content_was_actually_removed() {
        assertEquals(100, PortalHeight.next(300, 100, 1f))
    }

    @Test
    fun a_report_of_zero_leaves_the_current_height_alone() {
        // This is the aspect-ratio and unlaid-out-iframe case: at that instant
        // the document has no height yet, and taking it would collapse the box.
        assertEquals(300, PortalHeight.next(300, 0, 2.75f))
    }

    @Test
    fun an_unchanged_report_returns_the_same_value() {
        assertEquals(300, PortalHeight.next(300, 300, 1f))
    }
}
