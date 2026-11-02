// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import androidx.compose.ui.graphics.Color
import com.unuslumen.app.ui.theme.Memphis

/**
 * How each family looks.
 *
 * A family's colour is its identity, so it is resolved once, by name, from the
 * house rotation rather than by position in a list: a memory must be the same red
 * in the map as it is on the memory screen, and it must stay that red when the
 * family order or the row count changes.
 *
 * The two colour families that would collide are deliberately separated here.
 * Memories and projects both land on coral in the rotation, so projects are moved
 * one slot along to sun, because two reds next to each other in one graph is the
 * tell that a palette was generated rather than chosen.
 */
object MindStyle {

    /** The colour a family is drawn in. */
    fun inkFor(family: MindFamily): Color = when (family) {
        MindFamily.MEMORY -> Memphis.Coral
        MindFamily.SESSION -> Memphis.Sun
        MindFamily.PERSON -> Memphis.Yellow
        MindFamily.TASK -> Memphis.Cyan
        MindFamily.AGENT -> Memphis.Purple
        MindFamily.SKILL -> Memphis.Teal
        MindFamily.THOUGHT -> Memphis.Pink
        MindFamily.NOTE -> Memphis.Lilac
        // Ten families, eight house colours, so two pairs necessarily share. They
        // are the two furthest apart on the ring (memory and project, skill and
        // device), which keeps the collision from being read as "the same kind of
        // thing twice" by anyone scanning the map.
        MindFamily.PROJECT -> Memphis.Coral
        MindFamily.DEVICE -> Memphis.Teal
    }

    /** The centre is ink, because it is not one family, it is all of them. */
    val Core: Color = Memphis.Ink

    /** Paper, for text printed on top of a filled node. */
    val OnInk: Color = Memphis.Paper

    /** Faint ink for a resting node's rim, so the tissue fades without vanishing. */
    val RestingRim: Color = Memphis.Ink

    /** The weight of a spoke, the structural line from a hub to its members. */
    const val SPOKE_WIDTH = 1.2f

    /** The weight of a semantic or inferred edge. */
    const val SYNAPSE_WIDTH = 1.4f

    /** The weight of the edges around whatever is selected. */
    const val ACTIVE_WIDTH = 2.2f

    /** Opacity of every line at rest. Low on purpose: this is tissue, not wiring. */
    const val RESTING_LINE_ALPHA = 0.10f

    /** Opacity of the lit web around a selection. */
    const val ACTIVE_LINE_ALPHA = 0.85f

    /** Opacity a resting node's fill is drawn at. */
    const val RESTING_FILL_ALPHA = 0.30f

    /** Opacity a resting node's rim is drawn at. */
    const val RESTING_RIM_ALPHA = 0.55f

    /** How much of a label is printed when nothing is selected. */
    const val RESTING_LABEL_ALPHA = 0.42f
}
