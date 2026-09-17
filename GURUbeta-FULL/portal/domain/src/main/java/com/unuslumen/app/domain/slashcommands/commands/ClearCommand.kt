package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation

/**
 * /clear (aliases /new /reset) — start a fresh conversation. Returns a
 * NewConversation result; the host persists the old conversation first, exactly
 * as its own clear flow already does.
 */
class ClearCommand : SlashCommandAction {
    override val key: String = "clear"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        return SlashCommandResult.NewConversation
    }
}