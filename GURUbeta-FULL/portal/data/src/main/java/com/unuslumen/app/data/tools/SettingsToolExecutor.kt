package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringSetPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class SettingsToolExecutor(
    private val getPreferenceUseCase: GetPreferenceUseCase,
    private val savePreferenceUseCase: SavePreferenceUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        SettingsToolDefinitions.GET_PREFERENCE -> getPreference(args)
        SettingsToolDefinitions.SAVE_PREFERENCE -> savePreference(args)
        SettingsToolDefinitions.GET_ALL_PREFERENCES -> getAllPreferences()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun getPreference(args: Map<String, Any?>): ToolExecutionResult {
        val key = args["key"] as? String ?: return ToolExecutionResult.error("Missing 'key'")
        val value = try { getPreferenceUseCase(stringPreferencesKey(key), "").first() }
        catch (_: Exception) { try { getPreferenceUseCase(intPreferencesKey(key), -1).first().toString() }
        catch (_: Exception) { try { getPreferenceUseCase(booleanPreferencesKey(key), false).first().toString() }
        catch (_: Exception) { try { getPreferenceUseCase(stringSetPreferencesKey(key), emptySet()).first().joinToString(", ") }
        catch (_: Exception) { "Unknown preference key: $key" } } } }
        val r = PreferenceResult(key, value); return ToolExecutionResult.success(r, json.encodeToString(PreferenceResult.serializer(), r))
    }

    private suspend fun savePreference(args: Map<String, Any?>): ToolExecutionResult {
        val key = args["key"] as? String ?: return ToolExecutionResult.error("Missing 'key'")
        val value = args["value"] as? String ?: return ToolExecutionResult.error("Missing 'value'")
        val booleanKeys = setOf(PrefsConstants.AI_TOOLS_ENABLED_KEY, PrefsConstants.VISION_ENABLED_KEY)
        if (key in booleanKeys) {
            val boolValue = value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Invalid boolean value: '$value'. Use 'true' or 'false'.")
            savePreferenceUseCase(booleanPreferencesKey(key), boolValue)
        } else { savePreferenceUseCase(stringPreferencesKey(key), value) }
        val r = PreferenceResult(key, value); return ToolExecutionResult.success(r, json.encodeToString(PreferenceResult.serializer(), r))
    }

    private suspend fun getAllPreferences(): ToolExecutionResult {
        val knownKeys = mapOf(
            PrefsConstants.AI_PROVIDER_KEY to "AI provider (0=None, 6=UnusLumen)", PrefsConstants.AI_TOOLS_ENABLED_KEY to "AI tools enabled",
            PrefsConstants.VISION_ENABLED_KEY to "Screen vision (Guru sees the user's screen every message)",
            PrefsConstants.USER_NAME_KEY to "User's name", PrefsConstants.EXCLUDED_CALENDARS_KEY to "Excluded calendar IDs",
            PrefsConstants.GURU_CUSTOM_THEME_KEY to "Custom theme JSON", PrefsConstants.GURU_LAYOUT_CONFIG_KEY to "Custom layout config JSON",
            "theme" to "App theme", "start_destination" to "Default start screen",
            "tasks_order" to "Tasks sort order", "notes_order" to "Notes sort order",
            "bookmarks_order" to "Bookmarks sort order", "journal_order" to "Journal sort order"
        )
        val prefs = mutableListOf<PreferenceEntry>()
        for ((key, desc) in knownKeys) {
            val value = try { getPreferenceUseCase(stringPreferencesKey(key), "").first() }
            catch (_: Exception) { try { getPreferenceUseCase(intPreferencesKey(key), -1).first().toString() }
            catch (_: Exception) { try { getPreferenceUseCase(booleanPreferencesKey(key), false).first().toString() }
            catch (_: Exception) { "unable to read" } } }
            if (value.isNotEmpty() && value != "-1") prefs.add(PreferenceEntry(key, value, desc))
        }
        val r = AllPreferencesResult(prefs); return ToolExecutionResult.success(r, json.encodeToString(AllPreferencesResult.serializer(), r))
    }
}