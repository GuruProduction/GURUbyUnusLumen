package com.unuslumen.app.preferences.domain.model

import kotlinx.serialization.Serializable

/**
 * Data-driven layout configuration that allows Guru to customize the app's spacing,
 * padding, elevation, and other layout properties at runtime.
 *
 * All values are in dp (density-independent pixels). When null, the app uses its
 * default hardcoded values.
 *
 * This object is persisted as JSON in SharedPreferences via GuruThemeRepository.
 */
@Serializable
data class LayoutConfig(
    // Spacing (in dp)
    val spacingExtraSmall: Int? = null,   // default 2dp
    val spacingSmall: Int? = null,         // default 4dp
    val spacingMedium: Int? = null,        // default 8dp
    val spacingLarge: Int? = null,          // default 16dp
    val spacingExtraLarge: Int? = null,     // default 24dp
    val spacingHuge: Int? = null,           // default 32dp

    // Card styling
    val cardElevation: Int? = null,         // default 2dp
    val cardCornerRadius: Int? = null,      // default 12dp (overrides theme corner radius for cards)
    val cardPadding: Int? = null,           // default 16dp
    val cardBorderWidth: Int? = null,       // default 0dp

    // List item styling
    val listItemPadding: Int? = null,       // default 12dp
    val listItemSpacing: Int? = null,       // default 8dp

    // Screen edge padding
    val screenPaddingHorizontal: Int? = null, // default 16dp
    val screenPaddingVertical: Int? = null,    // default 8dp

    // Section spacing
    val sectionSpacing: Int? = null,         // default 24dp
    val sectionHeaderPadding: Int? = null,  // default 16dp

    // Content width constraints
    val maxContentWidth: Int? = null,       // default null (unlimited)

    // Preset name (if applied from a preset)
    val presetName: String? = null
) {
    companion object {
        val DEFAULT = LayoutConfig()

        val COMPACT = LayoutConfig(
            spacingExtraSmall = 1,
            spacingSmall = 2,
            spacingMedium = 4,
            spacingLarge = 8,
            spacingExtraLarge = 12,
            spacingHuge = 16,
            cardElevation = 1,
            cardCornerRadius = 8,
            cardPadding = 8,
            cardBorderWidth = 0,
            listItemPadding = 8,
            listItemSpacing = 4,
            screenPaddingHorizontal = 8,
            screenPaddingVertical = 4,
            sectionSpacing = 12,
            sectionHeaderPadding = 8,
            presetName = "compact"
        )

        val SPACIOUS = LayoutConfig(
            spacingExtraSmall = 4,
            spacingSmall = 8,
            spacingMedium = 12,
            spacingLarge = 24,
            spacingExtraLarge = 32,
            spacingHuge = 48,
            cardElevation = 4,
            cardCornerRadius = 16,
            cardPadding = 24,
            cardBorderWidth = 0,
            listItemPadding = 16,
            listItemSpacing = 12,
            screenPaddingHorizontal = 24,
            screenPaddingVertical = 12,
            sectionSpacing = 32,
            sectionHeaderPadding = 24,
            presetName = "spacious"
        )

        val DENSE = LayoutConfig(
            spacingExtraSmall = 1,
            spacingSmall = 2,
            spacingMedium = 4,
            spacingLarge = 8,
            spacingExtraLarge = 12,
            spacingHuge = 16,
            cardElevation = 0,
            cardCornerRadius = 4,
            cardPadding = 8,
            cardBorderWidth = 1,
            listItemPadding = 4,
            listItemSpacing = 2,
            screenPaddingHorizontal = 8,
            screenPaddingVertical = 4,
            sectionSpacing = 8,
            sectionHeaderPadding = 8,
            presetName = "dense"
        )

        val PRESETS = mapOf(
            "default" to DEFAULT,
            "compact" to COMPACT,
            "spacious" to SPACIOUS,
            "dense" to DENSE
        )
    }
}