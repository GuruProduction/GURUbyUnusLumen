// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.compose.ui.graphics.Color

/**
 * The Memphis palette: flat, saturated ink on paper.
 *
 * Every colour here is a poster colour. They are chosen so no two adjacent
 * rooms share a family, which is what makes a list of forty rooms read as a
 * composed page rather than a wash. Nothing here glows, gradients or
 * brightens: the loudness comes from saturation and from hard black edges,
 * never from light.
 *
 * The paper and the ink are the two constants the whole screen is built from.
 * A room's colour is decoration; the ink is what carries the text.
 */
object Memphis {
    /** The page. Warm off-white, never pure white, so black edges have something to sit on. */
    val Paper = Color(0xFFFFFBF0)

    /** The line work. Near-black rather than pure black, which reads as printed ink. */
    val Ink = Color(0xFF12100E)

    /** Hard shadow offset used everywhere: the poster's printed drop. */
    val ShadowInk = Color(0xFF12100E)

    val Yellow = Color(0xFFFFCE1B)
    val Cyan = Color(0xFF5BC8E0)
    val Coral = Color(0xFFEE5B45)
    val Purple = Color(0xFF7A5AF8)
    val Teal = Color(0xFF15807A)
    val Pink = Color(0xFFFF8FA8)
    val Lilac = Color(0xFFC9B8FF)
    val Sun = Color(0xFFFFB020)

    /**
     * The rotation a room's colour comes from.
     *
     * Ordered so consecutive rooms are far apart on the wheel, because the list
     * is read top to bottom and two similar colours next to each other is what
     * makes a generated page look generated.
     */
    val Rotation: List<Color> = listOf(
        Yellow, Cyan, Coral, Purple, Teal, Pink, Lilac, Sun,
    )

    /** The colour for a room at a given position in the list. Stable, not random. */
    fun inkFor(position: Int): Color = Rotation[((position % Rotation.size) + Rotation.size) % Rotation.size]

    /**
     * A deterministic colour for a named room.
     *
     * Used where a position is not meaningful (grouping, or a room whose
     * order can change) but the same room must keep the same colour between
     * renders, because a room that changes colour feels broken.
     */
    fun inkForName(name: String): Color {
        var hash = 0
        for (character in name.lowercase()) {
            hash = (hash * 31 + character.code) and 0x7FFFFFFF
        }
        return Rotation[hash % Rotation.size]
    }

    /** Type that reads as a poster headline: heavy, tight, uppercase. */
    val DisplayFontName = "archivo_black"

    val DisplayFontLabel = "Archivo Black"

    /** The working sans for names and counts. Already bundled, already sharp. */
    val BodyFontName = "inter"

    val BodyFontLabel = "Inter"
}
