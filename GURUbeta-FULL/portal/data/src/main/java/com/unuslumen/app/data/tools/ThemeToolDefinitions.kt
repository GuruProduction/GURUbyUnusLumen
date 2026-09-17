package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ThemeToolDefinitions : ToolSetRegistration {
    const val GET_CURRENT_THEME = "get_current_theme"
    const val SET_THEME_COLOR = "set_theme_color"
    const val APPLY_THEME_PRESET = "apply_theme_preset"
    const val RESET_THEME = "reset_theme"
    const val EXPORT_THEME = "export_theme"
    const val IMPORT_THEME = "import_theme"
    const val LIST_THEME_PRESETS = "list_theme_presets"
    const val SET_THEME_CORNER_RADIUS = "set_theme_corner_radius"
    const val SET_THEME_FONT = "set_theme_font"
    const val LIST_AVAILABLE_FONTS = "list_available_fonts"
    const val RESET_THEME_FONT = "reset_theme_font"
    const val SET_FONT_SCALE = "set_font_scale"
    const val SET_CHAT_COLOUR = "set_chat_colour"
    const val RESET_CHAT_COLOUR = "reset_chat_colour"
    const val GET_LAYOUT_CONFIG = "get_layout_config"
    const val SET_LAYOUT_PROPERTY = "set_layout_property"
    const val APPLY_LAYOUT_PRESET = "apply_layout_preset"
    const val RESET_LAYOUT_CONFIG = "reset_layout_config"

    override val definitions = listOf(
        ToolDefinition(name = GET_CURRENT_THEME, description = "Get the current custom theme configuration. Returns all color values for both dark and light themes, corner radii, and the active preset name. Use this to see what the app currently looks like and what customisations are in place. If no custom theme is set, returns the default theme values.", category = "theme", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SET_THEME_COLOR, description = "Set a specific color in the app's theme. Use this to customise individual colors. The role parameter specifies which color to change. The color parameter must be a hex color string like #FF2DD1E7 (with alpha) or #2DD1E7 (without alpha). Changes apply immediately.", category = "theme", parameters = listOf(ToolParameter("role", ToolParameterType.String, true, "The color role to change, e.g. 'darkPrimary', 'lightSurface', 'darkBackground'."), ToolParameter("color", ToolParameterType.String, true, "The hex color value, e.g. '#FF2DD1E7' or '#2DD1E7'. Must start with #.")), permissions = emptyList()),
        ToolDefinition(name = APPLY_THEME_PRESET, description = "Apply a named theme preset to customise the app's appearance. Available presets: 'default', 'ocean', 'forest', 'sunset', 'minimalist'. Changes apply immediately.", category = "theme", parameters = listOf(ToolParameter("presetName", ToolParameterType.String, true, "The preset name to apply. Available: default, ocean, forest, sunset, minimalist")), permissions = emptyList()),
        ToolDefinition(name = RESET_THEME, description = "Reset the app's theme to the default warm paper theme.", category = "theme", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = EXPORT_THEME, description = "Export the current theme configuration as a JSON string. Use this to share your custom theme with others or to back up your theme settings. The JSON can be imported later using importTheme.", category = "theme", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = IMPORT_THEME, description = "Import a theme configuration from a JSON string. Use this to apply a theme that someone else exported. The JSON must be a valid GuruTheme configuration. Changes apply immediately.", category = "theme", parameters = listOf(ToolParameter("themeJson", ToolParameterType.String, true, "The JSON string containing the theme configuration. Must be a valid GuruTheme JSON.")), permissions = emptyList()),
        ToolDefinition(name = LIST_THEME_PRESETS, description = "List all available theme presets with descriptions. Use this to show your human what theme options are available.", category = "theme", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SET_THEME_CORNER_RADIUS, description = "Set the corner radius for UI elements. This controls how rounded the app's cards, buttons, and containers look.", category = "theme", parameters = listOf(ToolParameter("size", ToolParameterType.String, true, "Which corner radius size to set: 'small', 'medium', or 'large'"), ToolParameter("radius", ToolParameterType.Integer, true, "The corner radius in dp")), permissions = emptyList()),
        ToolDefinition(name = SET_THEME_FONT, description = "Set a custom font for a specific context in the app. Context can be 'app' (native UI elements), 'guru' (Guru's chat text), or 'user' (the user's chat text). Default context is 'app' if not specified.", category = "theme", parameters = listOf(ToolParameter("fontName", ToolParameterType.String, true, "The font name (without extension)"), ToolParameter("context", ToolParameterType.String, false, "Which context to apply the font to: 'app', 'guru', or 'user'. Defaults to 'app'.")), permissions = emptyList()),
        ToolDefinition(name = LIST_AVAILABLE_FONTS, description = "List all custom fonts available in the guru_fonts/ directory. These are .ttf or .otf files that can be used with setThemeFont.", category = "theme", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = RESET_THEME_FONT, description = "Reset a font back to its default. Context can be 'app' (resets to Rubik), 'guru' (resets to CaviarDreams), or 'user' (resets to MadeTommySoft). Defaults to 'app' if not specified.", category = "theme", parameters = listOf(ToolParameter("context", ToolParameterType.String, false, "Which context to reset: 'app', 'guru', or 'user'. Defaults to 'app'.")), permissions = emptyList()),
        ToolDefinition(name = SET_FONT_SCALE, description = "Set the font scale (text size multiplier) for a specific context. Context can be 'app', 'guru', or 'user'. A value of 1.0 is the default size. 1.5 makes text 50% bigger. 0.8 makes text 20% smaller.", category = "theme", parameters = listOf(ToolParameter("scale", ToolParameterType.Float, true, "The font scale multiplier. 1.0 = normal, 1.5 = 50% bigger, 0.8 = 20% smaller. Range 0.5 to 3.0."), ToolParameter("context", ToolParameterType.String, false, "Which context to apply the scale to: 'app', 'guru', or 'user'. Defaults to 'app'.")), permissions = emptyList()),
        ToolDefinition(name = SET_CHAT_COLOUR, description = "Set a fixed text colour for chat messages in the portal. Context can be 'guru' or 'user'. When a fixed colour is set, the rotating colour palette stops for that context.", category = "theme", parameters = listOf(ToolParameter("colour", ToolParameterType.String, true, "The hex colour value, e.g. '#D64A17' or '#FFD64A17'. Must start with #."), ToolParameter("context", ToolParameterType.String, true, "Which chat context to set the colour for: 'guru' or 'user'.")), permissions = emptyList()),
        ToolDefinition(name = RESET_CHAT_COLOUR, description = "Reset the chat text colour back to the rotating palette. Context can be 'guru' or 'user'. After reset, each new message gets a random colour from the rotation palette again.", category = "theme", parameters = listOf(ToolParameter("context", ToolParameterType.String, true, "Which chat context to reset: 'guru' or 'user'.")), permissions = emptyList()),
        ToolDefinition(name = GET_LAYOUT_CONFIG, description = "Get the current layout configuration. Returns all spacing, padding, elevation, and sizing values that control how the app's UI is laid out.", category = "theme", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SET_LAYOUT_PROPERTY, description = "Set a single layout property. This lets you fine-tune the app's spacing, padding, elevation, and sizing.", category = "theme", parameters = listOf(ToolParameter("key", ToolParameterType.String, true, "The layout property name"), ToolParameter("value", ToolParameterType.Integer, true, "The value in dp")), permissions = emptyList()),
        ToolDefinition(name = APPLY_LAYOUT_PRESET, description = "Apply a named layout preset. Presets change the overall density and spacing of the app. Available presets: 'default', 'compact', 'spacious', 'dense'.", category = "theme", parameters = listOf(ToolParameter("presetName", ToolParameterType.String, true, "The preset name: 'default', 'compact', 'spacious', or 'dense'")), permissions = emptyList()),
        ToolDefinition(name = RESET_LAYOUT_CONFIG, description = "Reset the layout configuration back to defaults. Removes all custom spacing, padding, elevation, and sizing overrides.", category = "theme", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = ThemeToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}