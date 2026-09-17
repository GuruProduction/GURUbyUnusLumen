package com.unuslumen.app.preferences.domain.model

import kotlinx.serialization.Serializable

/**
 * Data-driven theme configuration that allows Guru to customize the app's appearance at runtime.
 *
 * All color values are stored as hex strings (#AARRGGBB or #RRGGBB) for easy serialization
 * and for Guru to work with via the ThemeToolSet. When a value is null, the default
 * hardcoded color is used instead — this means partial customization is supported.
 *
 * Shape radii are stored as integers (dp values). When null, Material3 defaults are used.
 *
 * This object is persisted as JSON in SharedPreferences via GuruThemeRepository.
 */
@Serializable
data class GuruTheme(
    // Dark theme colors
    val darkPrimary: String? = null,
    val darkOnPrimary: String? = null,
    val darkSecondary: String? = null,
    val darkTertiary: String? = null,
    val darkBackground: String? = null,
    val darkOnBackground: String? = null,
    val darkSurface: String? = null,
    val darkOnSurface: String? = null,
    val darkOnSurfaceVariant: String? = null,
    val darkSurfaceVariant: String? = null,
    val darkSurfaceTint: String? = null,

    // Light theme colors
    val lightPrimary: String? = null,
    val lightOnPrimary: String? = null,
    val lightSecondary: String? = null,
    val lightTertiary: String? = null,
    val lightBackground: String? = null,
    val lightOnBackground: String? = null,
    val lightSurface: String? = null,
    val lightOnSurface: String? = null,
    val lightOnSurfaceVariant: String? = null,
    val lightSurfaceVariant: String? = null,
    val lightSurfaceTint: String? = null,

    // Shape customization (corner radii in dp)
    val cornerRadiusSmall: Int? = null,
    val cornerRadiusMedium: Int? = null,
    val cornerRadiusLarge: Int? = null,

    // Typography customization — App UI context
    val fontSizeScale: Float? = null,
    val customFontName: String? = null, // Name of a .ttf file in the guru_fonts/ directory (without extension)

    // Chat text — Guru context (Portal WebView)
    val guruChatFont: String? = null,        // Font name from guru_fonts/ or portal assets, null = default (CaviarDreams)
    val guruChatColour: String? = null,      // Fixed hex colour, null = use rotating palette
    val guruChatFontScale: Float? = null,    // Font size multiplier, null = 1.0

    // Chat text — User context (Portal WebView)
    val userChatFont: String? = null,        // Font name from guru_fonts/ or portal assets, null = default (MadeTommySoft)
    val userChatColour: String? = null,      // Fixed hex colour, null = use rotating palette
    val userChatFontScale: Float? = null,    // Font size multiplier, null = 1.0

    // Preset name (if applied from a preset)
    val presetName: String? = null
) {
    companion object {
        /** Default theme matching the hardcoded values in Color.kt */
        val DEFAULT = GuruTheme()

        /** Ocean preset — deep blues and teals */
        val OCEAN = GuruTheme(
            darkPrimary = "#FF00BCD4",
            darkOnPrimary = "#FFFFFFFF",
            darkSecondary = "#FF0D47A1",
            darkTertiary = "#FF0D47A1",
            darkBackground = "#FF0A1929",
            darkOnBackground = "#FFE0E0E0",
            darkSurface = "#FF112240",
            darkOnSurface = "#FFE0E0E0",
            darkOnSurfaceVariant = "#FFB0BEC5",
            darkSurfaceVariant = "#FF112240",
            darkSurfaceTint = "#FF112240",
            lightPrimary = "#FF0097A7",
            lightOnPrimary = "#FFFFFFFF",
            lightSecondary = "#FF1565C0",
            lightTertiary = "#FF1565C0",
            lightBackground = "#FFF5F9FC",
            lightOnBackground = "#FF0A1929",
            lightSurface = "#FFE8F4F8",
            lightOnSurface = "#FF0A1929",
            lightOnSurfaceVariant = "#FF37474F",
            lightSurfaceVariant = "#FFE8F4F8",
            lightSurfaceTint = "#FFE8F4F8",
            presetName = "ocean"
        )

        /** Forest preset — deep greens and earth tones */
        val FOREST = GuruTheme(
            darkPrimary = "#FF66BB6A",
            darkOnPrimary = "#FF000000",
            darkSecondary = "#FF2E7D32",
            darkTertiary = "#FF2E7D32",
            darkBackground = "#FF0D1B0E",
            darkOnBackground = "#FFE8F5E9",
            darkSurface = "#FF1B2E1B",
            darkOnSurface = "#FFE8F5E9",
            darkOnSurfaceVariant = "#FFA5D6A7",
            darkSurfaceVariant = "#FF1B2E1B",
            darkSurfaceTint = "#FF1B2E1B",
            lightPrimary = "#FF388E3C",
            lightOnPrimary = "#FFFFFFFF",
            lightSecondary = "#FF1B5E20",
            lightTertiary = "#FF1B5E20",
            lightBackground = "#FFF1F8E9",
            lightOnBackground = "#FF1B2E1B",
            lightSurface = "#FFE8F5E9",
            lightOnSurface = "#FF1B2E1B",
            lightOnSurfaceVariant = "#FF33691E",
            lightSurfaceVariant = "#FFE8F5E9",
            lightSurfaceTint = "#FFE8F5E9",
            presetName = "forest"
        )

        /** Sunset preset — warm oranges, reds, and purples */
        val SUNSET = GuruTheme(
            darkPrimary = "#FFFF7043",
            darkOnPrimary = "#FF000000",
            darkSecondary = "#FFAD1457",
            darkTertiary = "#FFAD1457",
            darkBackground = "#FF1A0A0A",
            darkOnBackground = "#FFFBE9E7",
            darkSurface = "#FF2D1515",
            darkOnSurface = "#FFFBE9E7",
            darkOnSurfaceVariant = "#FFFFAB91",
            darkSurfaceVariant = "#FF2D1515",
            darkSurfaceTint = "#FF2D1515",
            lightPrimary = "#FFFF5722",
            lightOnPrimary = "#FFFFFFFF",
            lightSecondary = "#FFC2185B",
            lightTertiary = "#FFC2185B",
            lightBackground = "#FFFFF3E0",
            lightOnBackground = "#FF3E2723",
            lightSurface = "#FFFFE0B2",
            lightOnSurface = "#FF3E2723",
            lightOnSurfaceVariant = "#FFBF360C",
            lightSurfaceVariant = "#FFFFE0B2",
            lightSurfaceTint = "#FFFFE0B2",
            presetName = "sunset"
        )

        /** Minimalist preset — muted grays with subtle accent */
        val MINIMALIST = GuruTheme(
            darkPrimary = "#FFB0BEC5",
            darkOnPrimary = "#FF000000",
            darkSecondary = "#FF455A64",
            darkTertiary = "#FF455A64",
            darkBackground = "#FF121212",
            darkOnBackground = "#FFECEFF1",
            darkSurface = "#FF1E1E1E",
            darkOnSurface = "#FFECEFF1",
            darkOnSurfaceVariant = "#FFB0BEC5",
            darkSurfaceVariant = "#FF1E1E1E",
            darkSurfaceTint = "#FF1E1E1E",
            lightPrimary = "#FF546E7A",
            lightOnPrimary = "#FFFFFFFF",
            lightSecondary = "#FF37474F",
            lightTertiary = "#FF37474F",
            lightBackground = "#FFFAFAFA",
            lightOnBackground = "#FF263238",
            lightSurface = "#FFF5F5F5",
            lightOnSurface = "#FF263238",
            lightOnSurfaceVariant = "#FF455A64",
            lightSurfaceVariant = "#FFF5F5F5",
            lightSurfaceTint = "#FFF5F5F5",
            presetName = "minimalist"
        )

        /** All available presets */
        val PRESETS = mapOf(
            "default" to DEFAULT,
            "ocean" to OCEAN,
            "forest" to FOREST,
            "sunset" to SUNSET,
            "minimalist" to MINIMALIST
        )

        /**
         * Parse a hex color string (#AARRGGBB or #RRGGBB) to a Long color value.
         * Returns null if the string is invalid.
         */
        fun parseColorHex(hex: String?): Long? {
            if (hex.isNullOrBlank()) return null
            return try {
                val cleaned = hex.removePrefix("#")
                val argb = when (cleaned.length) {
                    6 -> "FF$cleaned" // Add full alpha
                    8 -> cleaned
                    else -> return null
                }
                argb.toLong(16)
            } catch (_: Exception) {
                null
            }
        }
    }
}