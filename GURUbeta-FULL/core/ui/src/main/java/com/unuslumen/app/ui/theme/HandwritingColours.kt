package com.unuslumen.app.ui.theme

/**
 * Handwriting font colour rotations for GURU portal chat text.
 * Steven's chosen palettes, picked at random per message, independently from font.
 *
 * Guru rotation: Burnt Sienna, Baby Blue, Forest Green, Yellow Green
 * User rotation: Lint, Teal Green, Forest Green, Black
 */

val GURU_COLOUR_ROTATION: List<String> = listOf(
    "#D64A17", // Burnt Sienna
    "#90CCE5", // Baby Blue
    "#004D3A", // Forest Green
    "#C8BD00", // Yellow Green
)

val USER_COLOUR_ROTATION: List<String> = listOf(
    "#D7D7A8", // Lint
    "#00A385", // Teal Green
    "#00493A", // Forest Green
    "#001F17", // Black
)

val HANDWRITING_FONT_FAMILY_NAMES: List<String> = listOf("OriginalSalmon", "MiracleDays", "HugMeTight", "CaviarDreams", "MadeTommySoft")

fun randomGuruColour(): String = GURU_COLOUR_ROTATION.random()
fun randomUserColour(): String = USER_COLOUR_ROTATION.random()