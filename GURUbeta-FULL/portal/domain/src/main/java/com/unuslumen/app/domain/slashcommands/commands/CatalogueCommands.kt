package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashInvocation
import com.unuslumen.app.domain.slashcommands.SlashCommandSession

/**
 * /help — list every registered command grouped by category. The command
 * catalogue itself lives in the presentation registry; it is passed in as a
 * simple catalogue function so domain stays free of presentation types.
 */
class HelpCommand(
    private val catalogueText: () -> String,
) : SlashCommandAction {
    override val key: String = "help"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        return SlashCommandResult.Handled(catalogueText())
    }
}

/**
 * /commands — alias behaviour of /help: the full command list.
 */
class CommandsCommand(
    private val catalogueText: () -> String,
) : SlashCommandAction {
    override val key: String = "commands"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        return SlashCommandResult.Handled(catalogueText())
    }
}