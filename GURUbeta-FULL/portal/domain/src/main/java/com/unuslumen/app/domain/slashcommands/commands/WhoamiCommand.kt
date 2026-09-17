package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.first

/**
 * /whoami — report the display name the app knows for this user. Reads the
 * same preference the profile screen writes.
 */
class WhoamiCommand(
    private val getPreference: GetPreferenceUseCase,
) : SlashCommandAction {
    override val key: String = "whoami"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        val name = getPreference(stringPreferencesKey(PrefsConstants.USER_NAME_KEY), "").first()
        val reply = if (name.isBlank()) {
            "You are chatting as an anonymous user. Set your name in Settings to personalise Guru's replies."
        } else {
            "You are $name."
        }
        return SlashCommandResult.Handled(reply)
    }
}