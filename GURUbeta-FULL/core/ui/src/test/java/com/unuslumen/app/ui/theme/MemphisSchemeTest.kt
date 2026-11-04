// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Holds the scheme on Memphis, because issue 312 was a palette that had been
 * replaced in two files out of the whole chat surface.
 *
 * THE DEFECT. The Memphis palette was rolled out to the lobby and to two
 * screens, and everything else still read `MaterialTheme.colorScheme`, which was
 * built from the retired warm beige family. A user cannot see which token a
 * widget asks for, so what they saw was "the chat bar and the thinking blocks
 * are the wrong colour", and every release after that would have needed someone
 * to notice it again.
 *
 * The rule these tests pin: the scheme is Memphis, every surface token agrees
 * about the page colour, and no retired beige or magenta value survives in it.
 */
class MemphisSchemeTest {

    private val scheme: ColorScheme = applyGuruThemeLight(theme = null)

    @Test
    fun thePageIsMemphisPaperNotTheRetiredBeige() {
        assertEquals("background must be Memphis.Paper", Memphis.Paper, scheme.background)
        assertEquals("surface must be Memphis.Paper", Memphis.Paper, scheme.surface)
        assertEquals("surfaceVariant must be Memphis.Paper", Memphis.Paper, scheme.surfaceVariant)

        // The exact values that shipped before this fix. If either reappears, the
        // chat bar is beige beside a paper page again.
        val retiredCard = Color(0xFFF0E8DA)
        val retiredBackground = Color(0xFFEDE4D3)
        assertNotEquals("the retired card beige is back", retiredCard, scheme.surface)
        assertNotEquals("the retired page beige is back", retiredBackground, scheme.background)
    }

    @Test
    fun everySurfaceTokenAgreesAboutThePageColour() {
        // A card and a page that disagree by one shade is exactly how a screen
        // ends up looking assembled rather than drawn.
        val surfaces = listOf(
            "surface" to scheme.surface,
            "surfaceVariant" to scheme.surfaceVariant,
            "surfaceTint" to scheme.surfaceTint,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceBright" to scheme.surfaceBright,
        )
        for ((name, value) in surfaces) {
            assertEquals("$name must be Memphis.Paper", Memphis.Paper, value)
        }
    }

    @Test
    fun textColoursAreInkNotTheRetiredBrown() {
        assertEquals("onSurface must be Ink", Memphis.Ink, scheme.onSurface)
        assertEquals("onSurfaceVariant must be Ink", Memphis.Ink, scheme.onSurfaceVariant)
        assertEquals("onBackground must be Ink", Memphis.Ink, scheme.onBackground)
        assertNotEquals("the retired brown #2B241C is back", Color(0xFF2B241C), scheme.onSurfaceVariant)
    }

    @Test
    fun theBrandIsInkNotTheRetiredSlateBlue() {
        // primary is what the thinking block's label used, and a slate blue at 70%
        // opacity inside a paper card is the "horrible colour" that was reported.
        assertEquals("primary must be Ink", Memphis.Ink, scheme.primary)
        assertNotEquals("the retired slate blue is back", Color(0xFF5B7C99), scheme.primary)
    }

    @Test
    fun noTokenIsTheMaterialBaselinePurple() {
        // Anything not passed to lightColorScheme keeps Material's own baseline,
        // which is a purple family that belongs to a different design system.
        // That is how the tool-call card was drawn from a container nobody set.
        val baselinePurple = Color(0xFF6750A4)
        val tokens = listOf(
            "primary" to scheme.primary,
            "secondary" to scheme.secondary,
            "tertiary" to scheme.tertiary,
            "primaryContainer" to scheme.primaryContainer,
            "secondaryContainer" to scheme.secondaryContainer,
            "tertiaryContainer" to scheme.tertiaryContainer,
            "surfaceVariant" to scheme.surfaceVariant,
        )
        for ((name, value) in tokens) {
            assertNotEquals("$name fell through to the Material baseline", baselinePurple, value)
        }
    }

    @Test
    fun theContainerTokensAreSetFromMemphis() {
        // The tool-call card reads secondaryContainer, and the weather and comms
        // result cards read primaryContainer. Both were Material defaults.
        assertEquals("secondaryContainer must be Lilac", Memphis.Lilac, scheme.secondaryContainer)
        assertEquals("primaryContainer must be Lilac", Memphis.Lilac, scheme.primaryContainer)
        assertEquals("tertiaryContainer must be Cyan", Memphis.Cyan, scheme.tertiaryContainer)
        assertEquals("error must be Coral", Memphis.Coral, scheme.error)
    }

    @Test
    fun theOutlineIsInkSoBordersReadAsPrint() {
        assertEquals(Memphis.Ink, scheme.outline)
    }

    @Test
    fun noMemphisInkIsBlackOrWhite() {
        // The design ban: the palette is poster inks on warm paper, never a pure
        // black ground and never a pure white one. This guards the palette itself
        // rather than a token, so a future edit to Memphis.kt is caught here.
        assertNotEquals("Memphis.Ink must not be pure black", Color.Black, Memphis.Ink)
        assertNotEquals("Memphis.Paper must not be pure white", Color.White, Memphis.Paper)
        assertFalse("Memphis.Ink must stay near-black, not light", Memphis.Ink.luminance() > 0.2f)
        assertFalse("Memphis.Paper must stay near-white, not dark", Memphis.Paper.luminance() < 0.8f)
    }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
