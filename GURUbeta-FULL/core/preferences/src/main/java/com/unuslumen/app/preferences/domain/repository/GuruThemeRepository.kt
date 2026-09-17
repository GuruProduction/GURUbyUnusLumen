package com.unuslumen.app.preferences.domain.repository

import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.domain.model.LayoutConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository for persisting and observing GuruTheme and LayoutConfig configuration.
 * The theme is stored as a JSON string in DataStore preferences.
 */
interface GuruThemeRepository {

    /**
     * Get the current GuruTheme configuration as a Flow.
     * Emits GuruTheme.DEFAULT when no custom theme is set.
     */
    fun getGuruTheme(): Flow<GuruTheme>

    /**
     * Save a GuruTheme configuration.
     * Replaces any existing custom theme.
     */
    suspend fun saveGuruTheme(theme: GuruTheme)

    /**
     * Reset the theme to defaults by clearing the custom theme.
     */
    suspend fun resetGuruTheme()

    /**
     * Update a single color in the current theme.
     * @param role The color role name (e.g., "darkPrimary", "lightSurface")
     * @param hexColor The hex color string (#AARRGGBB or #RRGGBB)
     */
    suspend fun setThemeColor(role: String, hexColor: String)

    /**
     * Apply a named preset.
     * @param presetName One of: "default", "ocean", "forest", "sunset", "minimalist"
     */
    suspend fun applyPreset(presetName: String)

    /**
     * Set the chat font for a specific context (guru or user).
     * @param context "guru" or "user"
     * @param fontName Font name without extension, or null to reset to default
     */
    suspend fun setChatFont(context: String, fontName: String?)

    /**
     * Set the chat text colour for a specific context (guru or user).
     * @param context "guru" or "user"
     * @param hexColor Hex colour string, or null to reset to rotating palette
     */
    suspend fun setChatColour(context: String, hexColor: String?)

    /**
     * Set the chat font scale for a specific context (guru or user).
     * @param context "guru" or "user"
     * @param scale Font size multiplier (0.5 to 3.0), or null to reset to 1.0
     */
    suspend fun setChatFontScale(context: String, scale: Float?)

    // --- Layout Config ---

    /**
     * Get the current LayoutConfig as a Flow.
     * Emits LayoutConfig.DEFAULT when no custom layout is set.
     */
    fun getLayoutConfig(): Flow<LayoutConfig>

    /**
     * Save a LayoutConfig.
     */
    suspend fun saveLayoutConfig(config: LayoutConfig)

    /**
     * Reset the layout config to defaults.
     */
    suspend fun resetLayoutConfig()

    /**
     * Apply a named layout preset.
     * @param presetName One of: "default", "compact", "spacious", "dense"
     */
    suspend fun applyLayoutPreset(presetName: String)

    /**
     * Update a single layout property.
     * @param key The property name (e.g., "spacingLarge", "cardElevation")
     * @param value The value in dp
     */
    suspend fun setLayoutProperty(key: String, value: Int)
}