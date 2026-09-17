package com.unuslumen.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.unuslumen.app.guru.R

/**
 * Handwriting font registry for GURU portal chat text.
 * Font definitions and Compose FontFamily objects live here because they need
 * the app module's R class for the font resources.
 * Colour rotations live in HandwritingColours.kt in core/ui.
 */

data class HandwritingFontTheme(
    val name: String,
    val family: FontFamily,
    val guruColour: String,
    val userColour: String,
)

val HANDWRITING_FONTS: List<HandwritingFontTheme> = listOf(
    HandwritingFontTheme(
        name = "Original Salmon",
        family = FontFamily(Font(R.font.original_salmon)),
        guruColour = "#D64A17",
        userColour = "#D7D7A8",
    ),
    HandwritingFontTheme(
        name = "Miracle Days",
        family = FontFamily(Font(R.font.miracle_days)),
        guruColour = "#90CCE5",
        userColour = "#00A385",
    ),
    HandwritingFontTheme(
        name = "Hug Me Tight",
        family = FontFamily(Font(R.font.hug_me_tight)),
        guruColour = "#004D3A",
        userColour = "#00493A",
    ),
    HandwritingFontTheme(
        name = "Caviar Dreams",
        family = FontFamily(Font(R.font.caviar_dreams)),
        guruColour = "#D64A17",
        userColour = "#D7D7A8",
    ),
    HandwritingFontTheme(
        name = "Made Tommy Soft",
        family = FontFamily(Font(R.font.made_tommy_soft)),
        guruColour = "#90CCE5",
        userColour = "#00A385",
    ),
)

fun randomGuruFontTheme(): HandwritingFontTheme = HANDWRITING_FONTS.random()
fun randomUserFontTheme(): HandwritingFontTheme = HANDWRITING_FONTS.random()