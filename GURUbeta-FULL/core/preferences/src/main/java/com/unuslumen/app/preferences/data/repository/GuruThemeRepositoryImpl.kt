package com.unuslumen.app.preferences.data.repository

import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.domain.model.LayoutConfig
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.repository.GuruThemeRepository
import com.unuslumen.app.preferences.domain.repository.PreferenceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class GuruThemeRepositoryImpl(
    private val preferenceRepository: PreferenceRepository,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
) : GuruThemeRepository {

    companion object {
        private const val GURU_THEME_KEY = "guru_custom_theme"
        private const val LAYOUT_CONFIG_KEY = "guru_layout_config"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }

    override fun getGuruTheme(): Flow<GuruTheme> {
        return preferenceRepository.getPreference(stringPreferencesKey(GURU_THEME_KEY), "").map { jsonStr ->
            if (jsonStr.isBlank()) GuruTheme.DEFAULT
            else try {
                json.decodeFromString<GuruTheme>(jsonStr)
            } catch (_: Exception) {
                GuruTheme.DEFAULT
            }
        }
    }

    override suspend fun saveGuruTheme(theme: GuruTheme) {
        withContext(ioDispatcher) {
            val jsonStr = json.encodeToString(GuruTheme.serializer(), theme)
            preferenceRepository.savePreference(stringPreferencesKey(GURU_THEME_KEY), jsonStr)
        }
    }

    override suspend fun resetGuruTheme() {
        withContext(ioDispatcher) {
            preferenceRepository.savePreference(stringPreferencesKey(GURU_THEME_KEY), "")
        }
    }

    override suspend fun setThemeColor(role: String, hexColor: String) {
        withContext(ioDispatcher) {
            // Get current theme value
            val currentJson = preferenceRepository.getPreference(stringPreferencesKey(GURU_THEME_KEY), "").first()

            val currentTheme = if (currentJson.isBlank()) GuruTheme.DEFAULT
                else try { json.decodeFromString<GuruTheme>(currentJson) } catch (_: Exception) { GuruTheme.DEFAULT }

            // Update the specific color role
            val updatedTheme = updateColorRole(currentTheme, role, hexColor)
            val updatedJson = json.encodeToString(GuruTheme.serializer(), updatedTheme)
            preferenceRepository.savePreference(stringPreferencesKey(GURU_THEME_KEY), updatedJson)
        }
    }

    override suspend fun applyPreset(presetName: String) {
        val preset = GuruTheme.PRESETS[presetName.lowercase()]
            ?: throw IllegalArgumentException("Unknown preset: $presetName. Available: ${GuruTheme.PRESETS.keys}")
        saveGuruTheme(preset)
    }

    override suspend fun setChatFont(context: String, fontName: String?) {
        withContext(ioDispatcher) {
            val currentTheme = getCurrentThemeSync()
            val updated = when (context.lowercase()) {
                "guru" -> currentTheme.copy(guruChatFont = fontName)
                "user" -> currentTheme.copy(userChatFont = fontName)
                else -> throw IllegalArgumentException("Unknown chat font context: $context. Use 'guru' or 'user'.")
            }
            saveThemeSync(updated)
        }
    }

    override suspend fun setChatColour(context: String, hexColor: String?) {
        withContext(ioDispatcher) {
            val currentTheme = getCurrentThemeSync()
            val updated = when (context.lowercase()) {
                "guru" -> currentTheme.copy(guruChatColour = hexColor)
                "user" -> currentTheme.copy(userChatColour = hexColor)
                else -> throw IllegalArgumentException("Unknown chat colour context: $context. Use 'guru' or 'user'.")
            }
            saveThemeSync(updated)
        }
    }

    override suspend fun setChatFontScale(context: String, scale: Float?) {
        withContext(ioDispatcher) {
            val currentTheme = getCurrentThemeSync()
            val clamped = scale?.coerceIn(0.5f, 3.0f)
            val updated = when (context.lowercase()) {
                "guru" -> currentTheme.copy(guruChatFontScale = clamped)
                "user" -> currentTheme.copy(userChatFontScale = clamped)
                else -> throw IllegalArgumentException("Unknown chat font scale context: $context. Use 'guru' or 'user'.")
            }
            saveThemeSync(updated)
        }
    }

    private suspend fun getCurrentThemeSync(): GuruTheme {
        val currentJson = preferenceRepository.getPreference(stringPreferencesKey(GURU_THEME_KEY), "").first()
        return if (currentJson.isBlank()) GuruTheme.DEFAULT
        else try { json.decodeFromString<GuruTheme>(currentJson) } catch (_: Exception) { GuruTheme.DEFAULT }
    }

    private suspend fun saveThemeSync(theme: GuruTheme) {
        val jsonStr = json.encodeToString(GuruTheme.serializer(), theme)
        preferenceRepository.savePreference(stringPreferencesKey(GURU_THEME_KEY), jsonStr)
    }

    private fun updateColorRole(theme: GuruTheme, role: String, hexColor: String): GuruTheme {
        return when (role.lowercase().replace("_", "").replace("-", "")) {
            // Dark theme colors
            "darkprimary" -> theme.copy(darkPrimary = hexColor)
            "darkonprimary" -> theme.copy(darkOnPrimary = hexColor)
            "darksecondary" -> theme.copy(darkSecondary = hexColor)
            "darktertiary" -> theme.copy(darkTertiary = hexColor)
            "darkbackground" -> theme.copy(darkBackground = hexColor)
            "darkonbackground" -> theme.copy(darkOnBackground = hexColor)
            "darksurface" -> theme.copy(darkSurface = hexColor)
            "darkonsurface" -> theme.copy(darkOnSurface = hexColor)
            "darkonsurfacevariant" -> theme.copy(darkOnSurfaceVariant = hexColor)
            "darksurfacevariant" -> theme.copy(darkSurfaceVariant = hexColor)
            "darksurfacetint" -> theme.copy(darkSurfaceTint = hexColor)
            // Light theme colors
            "lightprimary" -> theme.copy(lightPrimary = hexColor)
            "lightonprimary" -> theme.copy(lightOnPrimary = hexColor)
            "lightsecondary" -> theme.copy(lightSecondary = hexColor)
            "lighttertiary" -> theme.copy(lightTertiary = hexColor)
            "lightbackground" -> theme.copy(lightBackground = hexColor)
            "lightonbackground" -> theme.copy(lightOnBackground = hexColor)
            "lightsurface" -> theme.copy(lightSurface = hexColor)
            "lightonsurface" -> theme.copy(lightOnSurface = hexColor)
            "lightonsurfacevariant" -> theme.copy(lightOnSurfaceVariant = hexColor)
            "lightsurfacevariant" -> theme.copy(lightSurfaceVariant = hexColor)
            "lightsurfacetint" -> theme.copy(lightSurfaceTint = hexColor)
            // Shape
            "cornerradiussmall" -> theme.copy(cornerRadiusSmall = hexColor.toIntOrNull())
            "cornerradiusmedium" -> theme.copy(cornerRadiusMedium = hexColor.toIntOrNull())
            "cornerradiuslarge" -> theme.copy(cornerRadiusLarge = hexColor.toIntOrNull())
            else -> throw IllegalArgumentException("Unknown color role: $role")
        }
    }

    // --- Layout Config Implementation ---

    override fun getLayoutConfig(): Flow<LayoutConfig> {
        return preferenceRepository.getPreference(stringPreferencesKey(LAYOUT_CONFIG_KEY), "").map { jsonStr ->
            if (jsonStr.isBlank()) LayoutConfig.DEFAULT
            else try {
                json.decodeFromString<LayoutConfig>(jsonStr)
            } catch (_: Exception) {
                LayoutConfig.DEFAULT
            }
        }
    }

    override suspend fun saveLayoutConfig(config: LayoutConfig) {
        withContext(ioDispatcher) {
            val jsonStr = json.encodeToString(LayoutConfig.serializer(), config)
            preferenceRepository.savePreference(stringPreferencesKey(LAYOUT_CONFIG_KEY), jsonStr)
        }
    }

    override suspend fun resetLayoutConfig() {
        withContext(ioDispatcher) {
            preferenceRepository.savePreference(stringPreferencesKey(LAYOUT_CONFIG_KEY), "")
        }
    }

    override suspend fun applyLayoutPreset(presetName: String) {
        val preset = LayoutConfig.PRESETS[presetName.lowercase()]
            ?: throw IllegalArgumentException("Unknown layout preset: $presetName. Available: ${LayoutConfig.PRESETS.keys}")
        saveLayoutConfig(preset)
    }

    override suspend fun setLayoutProperty(key: String, value: Int) {
        withContext(ioDispatcher) {
            val currentJson = preferenceRepository.getPreference(stringPreferencesKey(LAYOUT_CONFIG_KEY), "").first()
            val current = if (currentJson.isBlank()) LayoutConfig.DEFAULT
                else try { json.decodeFromString<LayoutConfig>(currentJson) } catch (_: Exception) { LayoutConfig.DEFAULT }

            val updated = updateLayoutProperty(current, key, value)
            val updatedJson = json.encodeToString(LayoutConfig.serializer(), updated)
            preferenceRepository.savePreference(stringPreferencesKey(LAYOUT_CONFIG_KEY), updatedJson)
        }
    }

    private fun updateLayoutProperty(config: LayoutConfig, key: String, value: Int): LayoutConfig {
        return when (key.lowercase().replace("_", "").replace("-", "")) {
            "spacingextrasmall" -> config.copy(spacingExtraSmall = value)
            "spacingsmall" -> config.copy(spacingSmall = value)
            "spacingmedium" -> config.copy(spacingMedium = value)
            "spacinglarge" -> config.copy(spacingLarge = value)
            "spacingextralarge" -> config.copy(spacingExtraLarge = value)
            "spacinghuge" -> config.copy(spacingHuge = value)
            "cardelevation" -> config.copy(cardElevation = value)
            "cardcornerradius" -> config.copy(cardCornerRadius = value)
            "cardpadding" -> config.copy(cardPadding = value)
            "cardborderwidth" -> config.copy(cardBorderWidth = value)
            "listitempadding" -> config.copy(listItemPadding = value)
            "listitemspacing" -> config.copy(listItemSpacing = value)
            "screenpaddinghorizontal" -> config.copy(screenPaddingHorizontal = value)
            "screenpaddingvertical" -> config.copy(screenPaddingVertical = value)
            "sectionspacing" -> config.copy(sectionSpacing = value)
            "sectionheaderpadding" -> config.copy(sectionHeaderPadding = value)
            "maxcontentwidth" -> config.copy(maxContentWidth = value)
            else -> throw IllegalArgumentException("Unknown layout property: $key. Available: spacingExtraSmall, spacingSmall, spacingMedium, spacingLarge, spacingExtraLarge, spacingHuge, cardElevation, cardCornerRadius, cardPadding, cardBorderWidth, listItemPadding, listItemSpacing, screenPaddingHorizontal, screenPaddingVertical, sectionSpacing, sectionHeaderPadding, maxContentWidth")
        }
    }
}