// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The height a portal WebView should be given, decided from what the document
 * reports (issue 477).
 *
 * WHY THIS IS A SEPARATE, ANDROID-FREE, OBJECT. The rule has three parts that
 * each caused a real defect and none of which needs a device to prove: the CSS
 * pixel to dp conversion, the clamp, and the anti-oscillation policy. Buried in
 * a composable they could only be tested by driving a WebView, which is why the
 * conversion was wrong for as long as it was (it divided by the SCREEN's density
 * instead of the view's, making every auto-measured portal roughly 2.75 times
 * too short on a 440dpi phone) and why nothing caught it.
 */
object PortalHeight {

    /** The smallest height ever applied. Below this the portal is unreadable. */
    const val MIN_DP = 50

    /**
     * The largest height ever applied.
     *
     * A portal that wants more than this scrolls inside its own box. The ceiling
     * exists because one runaway document would otherwise push a whole
     * conversation off the screen and take the chat with it.
     */
    const val MAX_DP = 4000

    /**
     * How much shorter a new measurement must be before it is believed.
     *
     * THE RULE IS "GROW FREELY, SHRINK RELUCTANTLY". A document that gets taller
     * is reporting new content and is always believed. A document that gets
     * shorter is usually reporting a transient: a reflow mid-animation, an image
     * that has not been laid back in, a font swap in progress. Believing every
     * shrink makes the box flicker on each frame of a chart animation, so a
     * shrink is only applied when it is larger than this threshold, which is a
     * genuine removal rather than a reflow.
     */
    const val SHRINK_THRESHOLD_DP = 24

    /**
     * Convert a height in CSS pixels into the dp value a Compose modifier wants.
     *
     * `density` is the WINDOW's density, not the screen's. The two are the same
     * on a phone whose app runs full screen and diverge as soon as a window, a
     * split-screen pane or a density override is in play, and the old code used
     * the screen's for a WebView that lays out in its own viewport.
     */
    fun cssPxToDp(cssPx: Int, density: Float): Int {
        if (cssPx <= 0) return 0
        val safeDensity = if (density <= 0f) 1f else density
        // Rounded UP, never down: a fractional result that is floored loses the
        // last line of text, and a portal that is one line short looks clipped
        // rather than short.
        return ceil(cssPx / safeDensity).roundToInt()
    }

    /**
     * The height to apply for a fresh report, given what is currently applied.
     *
     * Returns `current` unchanged when the new report should be ignored, so a
     * caller can assign the result unconditionally.
     */
    fun next(currentDp: Int, reportedCssPx: Int, density: Float): Int {
        if (reportedCssPx <= 0) return currentDp
        val wanted = cssPxToDp(reportedCssPx, density)
            .coerceAtLeast(MIN_DP)
            .coerceAtMost(MAX_DP)
        return when {
            wanted > currentDp -> wanted
            currentDp - wanted > SHRINK_THRESHOLD_DP -> wanted
            else -> currentDp
        }
    }
}
