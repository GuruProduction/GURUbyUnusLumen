package com.unuslumen.app.presentation

import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

/**
 * Shared /vision command logic — the single source of truth for the screen-vision
 * toggle. Used by every chat surface (Portal, Assistant) so behaviour is identical
 * everywhere. Handles the vision_enabled preference and returns a human-readable
 * confirmation for the local chat message.
 */
@Single
class VisionCommandHandler(
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase
) {

    /**
     * Handle a /vision command. Mode comes from the parsed positional arg:
     * "on" enables, "off" disables, anything else (including null) reports status.
     * Returns the confirmation text to show locally in the chat.
     */
    suspend fun handle(mode: String?): String {
        return when (mode?.lowercase()?.trim()) {
            "on" -> {
                savePreference(
                    booleanPreferencesKey(PrefsConstants.VISION_ENABLED_KEY),
                    true
                )
                "Screen vision is ON. Every message you send now includes a capture of your " +
                    "current screen. First capture may need screen permission — if Guru says he " +
                    "cannot see, ask him to run requestScreenCapturePermission and allow the dialog."
            }
            "off" -> {
                savePreference(
                    booleanPreferencesKey(PrefsConstants.VISION_ENABLED_KEY),
                    false
                )
                "Screen vision is OFF."
            }
            else -> {
                val current = getPreference(
                    booleanPreferencesKey(PrefsConstants.VISION_ENABLED_KEY),
                    false
                ).first()
                if (current) "Screen vision is ON. Guru sees your screen with every message."
                else "Screen vision is OFF."
            }
        }
    }
}