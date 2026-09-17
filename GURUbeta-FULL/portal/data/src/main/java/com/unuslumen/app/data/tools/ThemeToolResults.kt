package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class ThemeResult(val darkPrimary: String, val darkOnPrimary: String, val darkSecondary: String, val darkTertiary: String, val darkBackground: String, val darkOnBackground: String, val darkSurface: String, val darkOnSurface: String, val darkOnSurfaceVariant: String, val darkSurfaceVariant: String, val darkSurfaceTint: String, val lightPrimary: String, val lightOnPrimary: String, val lightSecondary: String, val lightTertiary: String, val lightBackground: String, val lightOnBackground: String, val lightSurface: String, val lightOnSurface: String, val lightOnSurfaceVariant: String, val lightSurfaceVariant: String, val lightSurfaceTint: String, val cornerRadiusSmall: Int, val cornerRadiusMedium: Int, val cornerRadiusLarge: Int, val fontSizeScale: Float, val customFontName: String, val guruChatFont: String, val guruChatColour: String, val guruChatFontScale: Float, val userChatFont: String, val userChatColour: String, val userChatFontScale: Float, val presetName: String, val isCustom: Boolean) : ToolResultData
@Serializable data class ThemeColorResult(val success: Boolean, val message: String) : ToolResultData
@Serializable data class ThemePresetResult(val success: Boolean, val message: String, val presetName: String) : ToolResultData
@Serializable data class ThemeResetResult(val success: Boolean, val message: String) : ToolResultData
@Serializable data class ThemeExportResult(val success: Boolean, val message: String, val themeJson: String) : ToolResultData
@Serializable data class ThemeImportResult(val success: Boolean, val message: String, val presetName: String? = null) : ToolResultData
@Serializable data class ThemePresetsResult(val presets: List<ThemePresetInfo>, val message: String) : ToolResultData
@Serializable data class ThemePresetInfo(val name: String, val description: String, val darkPrimary: String, val darkBackground: String, val lightPrimary: String, val lightBackground: String) : ToolResultData
@Serializable data class ThemeCornerRadiusResult(val success: Boolean, val message: String) : ToolResultData
@Serializable data class ThemeFontResult(val success: Boolean, val message: String) : ToolResultData
@Serializable data class FontScaleResult(val success: Boolean, val message: String) : ToolResultData
@Serializable data class ThemeFontsListResult(val fonts: List<String>, val currentFont: String? = null, val message: String) : ToolResultData
@Serializable data class LayoutConfigResult(val spacingExtraSmall: Int, val spacingSmall: Int, val spacingMedium: Int, val spacingLarge: Int, val spacingExtraLarge: Int, val spacingHuge: Int, val cardElevation: Int, val cardCornerRadius: Int, val cardPadding: Int, val cardBorderWidth: Int, val listItemPadding: Int, val listItemSpacing: Int, val screenPaddingHorizontal: Int, val screenPaddingVertical: Int, val sectionSpacing: Int, val sectionHeaderPadding: Int, val maxContentWidth: Int? = null, val presetName: String, val isCustom: Boolean) : ToolResultData
@Serializable data class LayoutPropertyResult(val success: Boolean, val message: String) : ToolResultData
@Serializable data class LayoutPresetResult(val success: Boolean, val message: String, val presetName: String? = null) : ToolResultData
@Serializable data class LayoutResetResult(val success: Boolean, val message: String) : ToolResultData