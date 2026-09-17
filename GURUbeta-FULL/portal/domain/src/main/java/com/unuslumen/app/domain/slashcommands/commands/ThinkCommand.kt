package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.first

/**
 * /think — show or set the thinking display level. Writes the same preference
 * key the Assistant screen's thinking selector reads, so both stay in sync.
 * With no arg it reports; with off/low/medium/high/xhigh it persists.
 */
class ThinkCommand(
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
) : SlashCommandAction {
    override val key: String = "think"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        val pref = stringPreferencesKey(PrefsConstants.THINKING_LEVEL_KEY)
        val requested = invocation.values["level"]?.lowercase()?.trim() ?: invocation.rawArgs?.trim()?.lowercase()
        if (requested == null || requested !in VALID_LEVELS) {
            val current = getPreference(pref, "medium").first()
            return SlashCommandResult.Handled(
                "Thinking level is currently ${current.uppercase()}. Set it with /think off, low, medium, high or xhigh."
            )
        }
        savePreference(pref, requested)
        return SlashCommandResult.Handled("Thinking level set to ${requested.uppercase()}.")
    }

    private companion object {
        val VALID_LEVELS = setOf("off", "low", "medium", "high", "xhigh")
    }
}