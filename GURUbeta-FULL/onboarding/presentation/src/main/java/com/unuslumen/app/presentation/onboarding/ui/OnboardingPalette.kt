// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import androidx.compose.ui.graphics.Color

/**
 * The tile palette.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE CHAPTER ACCENTS.
 *
 * A chapter accent is the colour of the stretch of the flow you are in: it fills
 * the button, the slider, the newest node. That is one colour per screen.
 *
 * A tile is the opposite job. Six language tiles all painted the same red would
 * be a form with a highlight. Six tiles in six different colours is the thing he
 * actually asked for: pops of bright colour, things to interact with, a screen
 * that looks alive before you touch anything. The colour is the material the
 * option is made of, and it belongs to the option, not to the chapter.
 *
 * So a group of tiles takes a starting offset and walks this list. The result is
 * stable per question: the same question always shows the same colours in the
 * same order, because the offset is derived from the question key rather than
 * from the answer. A colour that changed when you tapped it would be noise.
 *
 * EVERY COLOUR HERE CLEARS 3:1 AGAINST WHITE, so it reads as a shape. When it is
 * used as a fill, [OnboardingSkin.onColour] decides whether the label on top is
 * white or ink, by measured luminance. The amber and the olive take ink; the
 * rest take white. That is decided once, in one place, not at each call site.
 */
object OnboardingPalette {

    /** Ten accents. One row of a paint chart, not twenty. */
    val tiles: List<Color> = listOf(
        Color(0xFFE24A2E), // tomato
        Color(0xFF1C6EA8), // ocean
        Color(0xFF1F8A4C), // leaf
        Color(0xFF6A44C0), // violet
        Color(0xFFE08A1E), // amber
        Color(0xFF12857F), // teal
        Color(0xFFA93A86), // magenta
        Color(0xFF3556CC), // indigo
        Color(0xFF7A9A18), // olive
        Color(0xFFC23A55)  // rose
    )

    /**
     * The colour for one position in a group.
     *
     * [seed] keeps a group stable across recomposition and across the flow: it is
     * derived from the question key, so "languages" always starts on the same
     * colour and always runs in the same order.
     */
    fun colourFor(seed: Int, index: Int): Color =
        tiles[Math.floorMod(seed + index, tiles.size)]

    /**
     * One tone from a single hue, for a grid where every tile is the same family.
     *
     * WHY THIS EXISTS. [colourFor] hands every option a different colour, which
     * on a four-option question is a bright, cheerful grid and on an eleven-option
     * question is confetti. He called that ugly, and he is right: eleven unrelated
     * hues on one screen is not a palette, it is noise.
     *
     * This walks one base colour through a small family instead, so a grid reads
     * as one material in several weights. The steps are deliberately small, so the
     * screen is one colour at a glance and the individual tiles are still
     * distinguishable. The base colour comes from the chapter, which means a list
     * question now looks like the chapter it belongs to.
     *
     * The lightening is a blend toward white rather than an alpha wash, because an
     * alpha wash over the white ground produces the same pale grey that made the
     * previous tiles look washed out. A blend keeps the hue at full saturation.
     */
    fun toneFor(base: Color, index: Int): Color {
        val steps = listOf(0f, 0.14f, 0.06f, 0.20f, 0.10f, 0.16f)
        val toward = steps[Math.floorMod(index, steps.size)]
        return Color(
            red = base.red + (1f - base.red) * toward,
            green = base.green + (1f - base.green) * toward,
            blue = base.blue + (1f - base.blue) * toward,
            alpha = 1f
        )
    }

    /**
     * A deterministic seed for a string.
     *
     * A plain rolling hash. It only has to be stable within one build and spread
     * different questions across different starting colours, and this does both
     * without a dependency.
     */
    fun seedFor(text: String): Int {
        var hash = 0
        for (character in text) {
            hash = hash * 31 + character.code
        }
        return Math.floorMod(hash, tiles.size)
    }

    /**
     * The resting wash behind a tile that is not selected.
     *
     * Strong enough that a grid of six tiles reads as six colours before you
     * touch anything, which is the whole point. A 0.10 tint over white is still
     * obviously that colour; anything lighter reads as grey.
     */
    fun washOf(colour: Color): Color = colour.copy(alpha = 0.12f)

    /**
     * The ring colour around a tile that is not selected. A hairline of the
     * colour itself, so the tile has an edge made of its own material rather
     * than a grey border.
     */
    fun edgeOf(colour: Color): Color = colour.copy(alpha = 0.22f)

    /** The label colour on a filled tile. */
    fun onTile(colour: Color): Color = OnboardingSkin.onColour(colour)
}
