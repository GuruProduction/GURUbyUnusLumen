package com.unuslumen.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.unuslumen.app.preferences.domain.model.GuruTheme as GuruThemeModel
import java.io.File

/**
 * Compose-specific extensions for GuruTheme.
 * The data class itself lives in core/preferences for serialization.
 * This file provides Compose Color parsing, font loading, and theme application functions.
 */

/** Directory where Guru stores custom font files */
const val GURU_FONTS_DIR = "guru_fonts"

/**
 * Parse a hex color string to a Compose Color.
 * Returns null if the string is invalid or null.
 */
fun GuruThemeModel.parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        val cleaned = hex.removePrefix("#")
        val argb = when (cleaned.length) {
            6 -> "FF$cleaned"
            8 -> cleaned
            else -> return null
        }
        Color(argb.toLong(16))
    } catch (_: Exception) {
        null
    }
}

/**
 * Convert a Compose Color to a hex string (#AARRGGBB).
 */
fun Color.toHex(): String {
    val argb = this.value.toLong() and 0xFFFFFFFF
    return "#${argb.toString(16).uppercase().padStart(8, '0')}"
}

/**
 * Load a custom FontFamily from the guru_fonts/ directory.
 * Returns null if the font file doesn't exist or can't be loaded.
 */
fun GuruThemeModel.loadCustomFont(context: Context): FontFamily? {
    val fontName = customFontName ?: return null
    val fontDir = File(context.filesDir, GURU_FONTS_DIR)
    val fontFile = File(fontDir, "$fontName.ttf")
    if (!fontFile.exists()) {
        // Try with .otf extension
        val otfFile = File(fontDir, "$fontName.otf")
        if (!otfFile.exists()) return null
        return FontFamily(Font(otfFile))
    }
    return FontFamily(Font(fontFile))
}

/**
 * List available custom fonts in the guru_fonts/ directory.
 */
fun listCustomFonts(context: Context): List<String> {
    val fontDir = File(context.filesDir, GURU_FONTS_DIR)
    if (!fontDir.exists()) return emptyList()
    return fontDir.listFiles()
        ?.filter { it.extension in listOf("ttf", "otf") }
        ?.map { it.nameWithoutExtension }
        ?: emptyList()
}

/**
 * Apply GuruTheme overrides to the light color scheme.
 * Any null values fall back to the hardcoded defaults.
 */
fun applyGuruThemeLight(theme: GuruThemeModel?): androidx.compose.material3.ColorScheme {
    if (theme == null) return lightColorSchemeDefaults
    return androidx.compose.material3.lightColorScheme(
        primary = theme.parseColor(theme.lightPrimary) ?: PrimaryColor,
        onPrimary = theme.parseColor(theme.lightOnPrimary) ?: OnPrimary,
        secondary = theme.parseColor(theme.lightSecondary) ?: SecondaryColor,
        tertiary = theme.parseColor(theme.lightTertiary) ?: TertiaryColor,
        background = theme.parseColor(theme.lightBackground) ?: LightBackgroundColor,
        onBackground = theme.parseColor(theme.lightOnBackground) ?: DarkGray,
        surface = theme.parseColor(theme.lightSurface) ?: LightCardColor,
        onSurface = theme.parseColor(theme.lightOnSurface) ?: DarkGray,
        onSurfaceVariant = theme.parseColor(theme.lightOnSurfaceVariant) ?: DarkGray,
        surfaceVariant = theme.parseColor(theme.lightSurfaceVariant) ?: LightCardColor,
        surfaceTint = theme.parseColor(theme.lightSurfaceTint) ?: LightCardColor,
        surfaceContainerHighest = theme.parseColor(theme.lightSurface) ?: LightCardColor,
        surfaceContainerLow = theme.parseColor(theme.lightSurface) ?: LightCardColor,
        surfaceContainerLowest = theme.parseColor(theme.lightSurface) ?: LightCardColor,
        surfaceContainer = theme.parseColor(theme.lightSurface) ?: LightCardColor,
        surfaceContainerHigh = theme.parseColor(theme.lightSurface) ?: LightCardColor,
        surfaceDim = theme.parseColor(theme.lightSurface) ?: LightCardColor,
        surfaceBright = theme.parseColor(theme.lightSurface) ?: LightCardColor,
    )
}

/** Cached default light scheme */
private val lightColorSchemeDefaults = androidx.compose.material3.lightColorScheme(
    primary = PrimaryColor,
    onPrimary = OnPrimary,
    secondary = SecondaryColor,
    tertiary = TertiaryColor,
    background = LightBackgroundColor,
    onBackground = DarkGray,
    onSurfaceVariant = DarkGray,
    surface = LightCardColor,
    surfaceTint = LightCardColor,
    surfaceVariant = LightCardColor,
    surfaceContainerHighest = LightCardColor,
    surfaceContainerLow = LightCardColor,
    surfaceContainerLowest = LightCardColor,
    surfaceContainer = LightCardColor,
    surfaceContainerHigh = LightCardColor,
    surfaceDim = LightCardColor,
    surfaceBright = LightCardColor
)