package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.domain.model.LayoutConfig
import com.unuslumen.app.preferences.domain.repository.GuruThemeRepository
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File

class ThemeToolExecutor(
    private val guruThemeRepository: GuruThemeRepository,
    private val context: Context
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val fontDir by lazy { File(context.filesDir, "guru_fonts") }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ThemeToolDefinitions.GET_CURRENT_THEME -> getCurrentTheme()
        ThemeToolDefinitions.SET_THEME_COLOR -> setThemeColor(args)
        ThemeToolDefinitions.APPLY_THEME_PRESET -> applyThemePreset(args)
        ThemeToolDefinitions.RESET_THEME -> resetTheme()
        ThemeToolDefinitions.EXPORT_THEME -> exportTheme()
        ThemeToolDefinitions.IMPORT_THEME -> importTheme(args)
        ThemeToolDefinitions.LIST_THEME_PRESETS -> listThemePresets()
        ThemeToolDefinitions.SET_THEME_CORNER_RADIUS -> setThemeCornerRadius(args)
        ThemeToolDefinitions.SET_THEME_FONT -> setThemeFont(args)
        ThemeToolDefinitions.LIST_AVAILABLE_FONTS -> listAvailableFonts()
        ThemeToolDefinitions.RESET_THEME_FONT -> resetThemeFont(args)
        ThemeToolDefinitions.SET_FONT_SCALE -> setFontScale(args)
        ThemeToolDefinitions.SET_CHAT_COLOUR -> setChatColour(args)
        ThemeToolDefinitions.RESET_CHAT_COLOUR -> resetChatColour(args)
        ThemeToolDefinitions.GET_LAYOUT_CONFIG -> getLayoutConfig()
        ThemeToolDefinitions.SET_LAYOUT_PROPERTY -> setLayoutProperty(args)
        ThemeToolDefinitions.APPLY_LAYOUT_PRESET -> applyLayoutPreset(args)
        ThemeToolDefinitions.RESET_LAYOUT_CONFIG -> resetLayoutConfig()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun getCurrentTheme(): ToolExecutionResult {
        val theme = try { guruThemeRepository.getGuruTheme().first() } catch (_: Exception) { GuruTheme.DEFAULT }
        val r = ThemeResult(
            darkPrimary = theme.darkPrimary ?: "not used (app is light only)", darkOnPrimary = theme.darkOnPrimary ?: "not used (app is light only)",
            darkSecondary = theme.darkSecondary ?: "not used (app is light only)", darkTertiary = theme.darkTertiary ?: "not used (app is light only)",
            darkBackground = theme.darkBackground ?: "not used (app is light only)", darkOnBackground = theme.darkOnBackground ?: "not used (app is light only)",
            darkSurface = theme.darkSurface ?: "not used (app is light only)", darkOnSurface = theme.darkOnSurface ?: "not used (app is light only)",
            darkOnSurfaceVariant = theme.darkOnSurfaceVariant ?: "not used (app is light only)", darkSurfaceVariant = theme.darkSurfaceVariant ?: "not used (app is light only)",
            darkSurfaceTint = theme.darkSurfaceTint ?: "not used (app is light only)", lightPrimary = theme.lightPrimary ?: "default (#FF2DD1E7)",
            lightOnPrimary = theme.lightOnPrimary ?: "default (#FFFFFFFF)", lightSecondary = theme.lightSecondary ?: "default (#FF5F12CA)",
            lightTertiary = theme.lightTertiary ?: "default (#FF5F12CA)", lightBackground = theme.lightBackground ?: "default (#FFEDE4D3)",
            lightOnBackground = theme.lightOnBackground ?: "default (#FF2B241C)", lightSurface = theme.lightSurface ?: "default (#FFF0E8DA)",
            lightOnSurface = theme.lightOnSurface ?: "default (#FF2B241C)", lightOnSurfaceVariant = theme.lightOnSurfaceVariant ?: "default (#FF2B241C)",
            lightSurfaceVariant = theme.lightSurfaceVariant ?: "default (#FFF0E8DA)", lightSurfaceTint = theme.lightSurfaceTint ?: "default (#FFF0E8DA)",
            cornerRadiusSmall = theme.cornerRadiusSmall ?: 4, cornerRadiusMedium = theme.cornerRadiusMedium ?: 8, cornerRadiusLarge = theme.cornerRadiusLarge ?: 16,
            fontSizeScale = theme.fontSizeScale ?: 1.0f, customFontName = theme.customFontName ?: "default (Rubik)",
            guruChatFont = theme.guruChatFont ?: "default (CaviarDreams)", guruChatColour = theme.guruChatColour ?: "rotating palette", guruChatFontScale = theme.guruChatFontScale ?: 1.0f,
            userChatFont = theme.userChatFont ?: "default (MadeTommySoft)", userChatColour = theme.userChatColour ?: "rotating palette", userChatFontScale = theme.userChatFontScale ?: 1.0f,
            presetName = theme.presetName ?: "default", isCustom = theme != GuruTheme.DEFAULT
        )
        return ToolExecutionResult.success(r, json.encodeToString(ThemeResult.serializer(), r))
    }

    private suspend fun setThemeColor(args: Map<String, Any?>): ToolExecutionResult {
        val role = args["role"] as? String ?: return ToolExecutionResult.error("Missing 'role'")
        val color = args["color"] as? String ?: return ToolExecutionResult.error("Missing 'color'")
        return try {
            val cleaned = color.removePrefix("#")
            if (cleaned.length != 6 && cleaned.length != 8) {
                val r = ThemeColorResult(success = false, message = "Invalid color format: $color. Use #RRGGBB or #AARRGGBB format.")
                return ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
            }
            cleaned.toLong(16)
            guruThemeRepository.setThemeColor(role, color)
            val r = ThemeColorResult(success = true, message = "Set $role to $color. The change is applied immediately.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        } catch (e: IllegalArgumentException) {
            val r = ThemeColorResult(success = false, message = "Unknown color role: $role. Use getCurrentTheme to see available roles.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        } catch (e: NumberFormatException) {
            val r = ThemeColorResult(success = false, message = "Invalid hex color: $color. Use format like #FF2DD1E7 or #2DD1E7.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        } catch (e: Exception) {
            val r = ThemeColorResult(success = false, message = "Failed to set theme color: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        }
    }

    private suspend fun applyThemePreset(args: Map<String, Any?>): ToolExecutionResult {
        val presetName = args["presetName"] as? String ?: return ToolExecutionResult.error("Missing 'presetName'")
        return try {
            guruThemeRepository.applyPreset(presetName)
            val r = ThemePresetResult(success = true, message = "Applied '$presetName' theme preset. The app's appearance has been updated immediately.", presetName = presetName.lowercase())
            ToolExecutionResult.success(r, json.encodeToString(ThemePresetResult.serializer(), r))
        } catch (e: IllegalArgumentException) {
            val r = ThemePresetResult(success = false, message = "Unknown preset: $presetName. Available presets: ${GuruTheme.PRESETS.keys.joinToString(", ")}", presetName = presetName)
            ToolExecutionResult.success(r, json.encodeToString(ThemePresetResult.serializer(), r))
        } catch (e: Exception) {
            val r = ThemePresetResult(success = false, message = "Failed to apply preset: ${e.message}", presetName = presetName)
            ToolExecutionResult.success(r, json.encodeToString(ThemePresetResult.serializer(), r))
        }
    }

    private suspend fun resetTheme(): ToolExecutionResult {
        return try {
            guruThemeRepository.resetGuruTheme()
            val r = ThemeResetResult(success = true, message = "Theme reset to the default warm paper theme.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeResetResult.serializer(), r))
        } catch (e: Exception) {
            val r = ThemeResetResult(success = false, message = "Failed to reset theme: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ThemeResetResult.serializer(), r))
        }
    }

    private suspend fun exportTheme(): ToolExecutionResult {
        val theme = try { guruThemeRepository.getGuruTheme().first() } catch (_: Exception) { GuruTheme.DEFAULT }
        val jsonStr = Json.encodeToString(GuruTheme.serializer(), theme)
        val r = ThemeExportResult(success = true, message = "Theme exported successfully. Share this JSON to let someone else use your theme.", themeJson = jsonStr)
        return ToolExecutionResult.success(r, json.encodeToString(ThemeExportResult.serializer(), r))
    }

    private suspend fun importTheme(args: Map<String, Any?>): ToolExecutionResult {
        val themeJson = args["themeJson"] as? String ?: return ToolExecutionResult.error("Missing 'themeJson'")
        return try {
            val theme = json.decodeFromString<GuruTheme>(themeJson)
            guruThemeRepository.saveGuruTheme(theme)
            val r = ThemeImportResult(success = true, message = "Theme imported and applied successfully.", presetName = theme.presetName ?: "custom")
            ToolExecutionResult.success(r, json.encodeToString(ThemeImportResult.serializer(), r))
        } catch (e: Exception) {
            val r = ThemeImportResult(success = false, message = "Failed to import theme: ${e.message}. Make sure the JSON is a valid theme configuration.", presetName = null)
            ToolExecutionResult.success(r, json.encodeToString(ThemeImportResult.serializer(), r))
        }
    }

    private suspend fun listThemePresets(): ToolExecutionResult {
        val presets = GuruTheme.PRESETS.map { (name, theme) ->
            ThemePresetInfo(
                name = name,
                description = when (name) {
                    "default" -> "Warm paper theme with gold accents — aged cream background, warm ink text"
                    "ocean" -> "Deep blues and teals — calm and professional"
                    "forest" -> "Deep greens and earth tones — natural and easy on the eyes"
                    "sunset" -> "Warm oranges, reds, and purples — cozy and inviting"
                    "minimalist" -> "Muted grays with subtle accent — clean and understated"
                    else -> "Custom theme"
                },
                darkPrimary = theme.darkPrimary ?: "default", darkBackground = theme.darkBackground ?: "default",
                lightPrimary = theme.lightPrimary ?: "default", lightBackground = theme.lightBackground ?: "default"
            )
        }
        val r = ThemePresetsResult(presets = presets, message = "Available theme presets. Use applyThemePreset to apply one.")
        return ToolExecutionResult.success(r, json.encodeToString(ThemePresetsResult.serializer(), r))
    }

    private suspend fun setThemeCornerRadius(args: Map<String, Any?>): ToolExecutionResult {
        val size = args["size"] as? String ?: return ToolExecutionResult.error("Missing 'size'")
        val radius = (args["radius"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'radius'")
        return try {
            val role = when (size.lowercase()) {
                "small" -> "cornerRadiusSmall"
                "medium" -> "cornerRadiusMedium"
                "large" -> "cornerRadiusLarge"
                else -> {
                    val r = ThemeCornerRadiusResult(success = false, message = "Unknown size: $size. Use 'small', 'medium', or 'large'.")
                    return ToolExecutionResult.success(r, json.encodeToString(ThemeCornerRadiusResult.serializer(), r))
                }
            }
            guruThemeRepository.setThemeColor(role, radius.toString())
            val r = ThemeCornerRadiusResult(success = true, message = "Set $size corner radius to ${radius}dp. Changes apply immediately.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeCornerRadiusResult.serializer(), r))
        } catch (e: Exception) {
            val r = ThemeCornerRadiusResult(success = false, message = "Failed to set corner radius: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ThemeCornerRadiusResult.serializer(), r))
        }
    }

    private suspend fun setThemeFont(args: Map<String, Any?>): ToolExecutionResult {
        val fontName = args["fontName"] as? String ?: return ToolExecutionResult.error("Missing 'fontName'")
        val context = (args["context"] as? String ?: "app").lowercase().trim()
        return try {
            when (context) {
                "app" -> {
                    val ttfFile = File(fontDir, "$fontName.ttf"); val otfFile = File(fontDir, "$fontName.otf")
                    if (!ttfFile.exists() && !otfFile.exists()) {
                        val r = ThemeFontResult(success = false, message = "Font file not found: $fontName. Place .ttf or .otf files in the guru_fonts/ directory first. Available fonts: ${listFontFiles().joinToString(", ").ifBlank { "none" }}")
                        return ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                    }
                    val currentTheme = guruThemeRepository.getGuruTheme().first()
                    guruThemeRepository.saveGuruTheme(currentTheme.copy(customFontName = fontName))
                    val r = ThemeFontResult(success = true, message = "App UI font set to '$fontName'. Changes apply immediately.")
                    ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                }
                "guru", "user" -> {
                    val builtInFonts = listOf("OriginalSalmon", "MiracleDays", "HugMeTight", "CaviarDreams", "MadeTommySoft")
                    val isBuiltIn = fontName in builtInFonts
                    val isInGuruFonts = File(fontDir, "$fontName.ttf").exists() || File(fontDir, "$fontName.otf").exists()
                    if (!isBuiltIn && !isInGuruFonts) {
                        val r = ThemeFontResult(success = false, message = "Font not found: '$fontName'. Check guru_fonts/ directory or use a built-in font: ${builtInFonts.joinToString(", ")}. Also available in guru_fonts/: ${listFontFiles().joinToString(", ").ifBlank { "none" }}")
                        return ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                    }
                    guruThemeRepository.setChatFont(context, fontName)
                    val contextLabel = if (context == "guru") "Guru chat text" else "User chat text"
                    val r = ThemeFontResult(success = true, message = "$contextLabel font set to '$fontName'. Changes apply immediately.")
                    ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                }
                else -> {
                    val r = ThemeFontResult(success = false, message = "Unknown context: '$context'. Use 'app', 'guru', or 'user'.")
                    ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                }
            }
        } catch (e: Exception) {
            val r = ThemeFontResult(success = false, message = "Failed to set font: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
        }
    }

    private suspend fun listAvailableFonts(): ToolExecutionResult {
        val fonts = listFontFiles()
        val currentTheme = try { guruThemeRepository.getGuruTheme().first() } catch (_: Exception) { GuruTheme.DEFAULT }
        val r = ThemeFontsListResult(
            fonts = fonts, currentFont = currentTheme.customFontName,
            message = if (fonts.isEmpty()) "No custom fonts found in guru_fonts/ directory. Use writeFile to add .ttf or .otf files there."
            else "Available custom fonts: ${fonts.joinToString(", ")}. Current font: ${currentTheme.customFontName ?: "default (Rubik)"}"
        )
        return ToolExecutionResult.success(r, json.encodeToString(ThemeFontsListResult.serializer(), r))
    }

    private suspend fun resetThemeFont(args: Map<String, Any?>): ToolExecutionResult {
        val context = (args["context"] as? String ?: "app").lowercase().trim()
        return try {
            when (context) {
                "app" -> {
                    val currentTheme = guruThemeRepository.getGuruTheme().first()
                    guruThemeRepository.saveGuruTheme(currentTheme.copy(customFontName = null))
                    val r = ThemeFontResult(success = true, message = "App UI font reset to default (Rubik). Changes apply immediately.")
                    ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                }
                "guru", "user" -> {
                    guruThemeRepository.setChatFont(context, null)
                    val contextLabel = if (context == "guru") "Guru chat text" else "User chat text"
                    val defaultFont = if (context == "guru") "CaviarDreams" else "MadeTommySoft"
                    val r = ThemeFontResult(success = true, message = "$contextLabel font reset to default ($defaultFont). Changes apply immediately.")
                    ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                }
                else -> {
                    val r = ThemeFontResult(success = false, message = "Unknown context: '$context'. Use 'app', 'guru', or 'user'.")
                    ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
                }
            }
        } catch (e: Exception) {
            val r = ThemeFontResult(success = false, message = "Failed to reset font: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ThemeFontResult.serializer(), r))
        }
    }

    private suspend fun setFontScale(args: Map<String, Any?>): ToolExecutionResult {
        val scale = (args["scale"] as? Number)?.toFloat() ?: return ToolExecutionResult.error("Missing 'scale'")
        val context = (args["context"] as? String ?: "app").lowercase().trim()
        return try {
            val clamped = scale.coerceIn(0.5f, 3.0f)
            when (context) {
                "app" -> {
                    val currentTheme = guruThemeRepository.getGuruTheme().first()
                    guruThemeRepository.saveGuruTheme(currentTheme.copy(fontSizeScale = clamped))
                    val r = FontScaleResult(success = true, message = "App UI font scale set to $clamped. Text size has been updated across the native interface.")
                    ToolExecutionResult.success(r, json.encodeToString(FontScaleResult.serializer(), r))
                }
                "guru", "user" -> {
                    guruThemeRepository.setChatFontScale(context, clamped)
                    val contextLabel = if (context == "guru") "Guru chat text" else "User chat text"
                    val r = FontScaleResult(success = true, message = "$contextLabel font scale set to $clamped. Changes apply immediately.")
                    ToolExecutionResult.success(r, json.encodeToString(FontScaleResult.serializer(), r))
                }
                else -> {
                    val r = FontScaleResult(success = false, message = "Unknown context: '$context'. Use 'app', 'guru', or 'user'.")
                    ToolExecutionResult.success(r, json.encodeToString(FontScaleResult.serializer(), r))
                }
            }
        } catch (e: Exception) {
            val r = FontScaleResult(success = false, message = "Failed to set font scale: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(FontScaleResult.serializer(), r))
        }
    }

    private suspend fun setChatColour(args: Map<String, Any?>): ToolExecutionResult {
        val colour = args["colour"] as? String ?: return ToolExecutionResult.error("Missing 'colour'")
        val context = (args["context"] as? String ?: "").lowercase().trim()
        return try {
            val cleaned = colour.removePrefix("#")
            if (cleaned.length != 6 && cleaned.length != 8) {
                val r = ThemeColorResult(success = false, message = "Invalid colour format: $colour. Use #RRGGBB or #AARRGGBB format.")
                return ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
            }
            cleaned.toLong(16)
            if (context != "guru" && context != "user") {
                val r = ThemeColorResult(success = false, message = "Unknown context: '$context'. Use 'guru' or 'user'.")
                return ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
            }
            guruThemeRepository.setChatColour(context, colour)
            val contextLabel = if (context == "guru") "Guru chat text" else "User chat text"
            val r = ThemeColorResult(success = true, message = "$contextLabel colour set to $colour. The rotating palette has been stopped for this context. Use resetChatColour to go back to rotating colours.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        } catch (e: NumberFormatException) {
            val r = ThemeColorResult(success = false, message = "Invalid hex colour: $colour. Use format like #D64A17 or #FFD64A17.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        } catch (e: Exception) {
            val r = ThemeColorResult(success = false, message = "Failed to set chat colour: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        }
    }

    private suspend fun resetChatColour(args: Map<String, Any?>): ToolExecutionResult {
        val context = (args["context"] as? String ?: "").lowercase().trim()
        return try {
            if (context != "guru" && context != "user") {
                val r = ThemeColorResult(success = false, message = "Unknown context: '$context'. Use 'guru' or 'user'.")
                return ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
            }
            guruThemeRepository.setChatColour(context, null)
            val contextLabel = if (context == "guru") "Guru chat text" else "User chat text"
            val r = ThemeColorResult(success = true, message = "$contextLabel colour reset to rotating palette. Each new message will get a random colour.")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        } catch (e: Exception) {
            val r = ThemeColorResult(success = false, message = "Failed to reset chat colour: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ThemeColorResult.serializer(), r))
        }
    }

    private suspend fun getLayoutConfig(): ToolExecutionResult {
        val config = try { guruThemeRepository.getLayoutConfig().first() } catch (_: Exception) { LayoutConfig.DEFAULT }
        val r = LayoutConfigResult(
            spacingExtraSmall = config.spacingExtraSmall ?: 2, spacingSmall = config.spacingSmall ?: 4,
            spacingMedium = config.spacingMedium ?: 8, spacingLarge = config.spacingLarge ?: 16,
            spacingExtraLarge = config.spacingExtraLarge ?: 24, spacingHuge = config.spacingHuge ?: 32,
            cardElevation = config.cardElevation ?: 2, cardCornerRadius = config.cardCornerRadius ?: 12,
            cardPadding = config.cardPadding ?: 16, cardBorderWidth = config.cardBorderWidth ?: 0,
            listItemPadding = config.listItemPadding ?: 12, listItemSpacing = config.listItemSpacing ?: 8,
            screenPaddingHorizontal = config.screenPaddingHorizontal ?: 16, screenPaddingVertical = config.screenPaddingVertical ?: 8,
            sectionSpacing = config.sectionSpacing ?: 24, sectionHeaderPadding = config.sectionHeaderPadding ?: 16,
            maxContentWidth = config.maxContentWidth, presetName = config.presetName ?: "default", isCustom = config != LayoutConfig.DEFAULT
        )
        return ToolExecutionResult.success(r, json.encodeToString(LayoutConfigResult.serializer(), r))
    }

    private suspend fun setLayoutProperty(args: Map<String, Any?>): ToolExecutionResult {
        val key = args["key"] as? String ?: return ToolExecutionResult.error("Missing 'key'")
        val value = (args["value"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'value'")
        return try {
            guruThemeRepository.setLayoutProperty(key, value)
            val r = LayoutPropertyResult(success = true, message = "Set $key to ${value}dp. Changes apply immediately.")
            ToolExecutionResult.success(r, json.encodeToString(LayoutPropertyResult.serializer(), r))
        } catch (e: Exception) {
            val r = LayoutPropertyResult(success = false, message = "Failed to set layout property: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(LayoutPropertyResult.serializer(), r))
        }
    }

    private suspend fun applyLayoutPreset(args: Map<String, Any?>): ToolExecutionResult {
        val presetName = args["presetName"] as? String ?: return ToolExecutionResult.error("Missing 'presetName'")
        return try {
            guruThemeRepository.applyLayoutPreset(presetName)
            val description = when (presetName.lowercase()) {
                "default" -> "Balanced spacing — the standard layout"
                "compact" -> "Tighter spacing, smaller cards — more content on screen"
                "spacious" -> "More breathing room, larger cards — easier on the eyes"
                "dense" -> "Minimal spacing, bordered cards — maximum information density"
                else -> "Custom layout preset"
            }
            val r = LayoutPresetResult(success = true, message = "Applied '$presetName' layout preset: $description. Changes apply immediately.", presetName = presetName)
            ToolExecutionResult.success(r, json.encodeToString(LayoutPresetResult.serializer(), r))
        } catch (e: Exception) {
            val r = LayoutPresetResult(success = false, message = "Failed to apply layout preset: ${e.message}", presetName = null)
            ToolExecutionResult.success(r, json.encodeToString(LayoutPresetResult.serializer(), r))
        }
    }

    private suspend fun resetLayoutConfig(): ToolExecutionResult {
        return try {
            guruThemeRepository.resetLayoutConfig()
            val r = LayoutResetResult(success = true, message = "Layout configuration reset to defaults. Changes apply immediately.")
            ToolExecutionResult.success(r, json.encodeToString(LayoutResetResult.serializer(), r))
        } catch (e: Exception) {
            val r = LayoutResetResult(success = false, message = "Failed to reset layout config: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(LayoutResetResult.serializer(), r))
        }
    }

    private fun listFontFiles(): List<String> {
        val fontDir = File(context.filesDir, "guru_fonts")
        if (!fontDir.exists()) return emptyList()
        return fontDir.listFiles()?.filter { it.extension in listOf("ttf", "otf") }?.map { it.nameWithoutExtension } ?: emptyList()
    }
}