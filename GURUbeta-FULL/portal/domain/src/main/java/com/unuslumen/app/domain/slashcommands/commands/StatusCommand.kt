package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation

/**
 * /status — report the live session snapshot: conversation, message count,
 * engine busyness, vision flag. Reads only from SlashCommandSession so it
 * works identically on Portal and Assistant.
 */
class StatusCommand : SlashCommandAction {
    override val key: String = "status"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        val lines = mutableListOf("Session status:")
        lines += "Conversation: ${session.conversationId?.take(8)?.plus("…") ?: "none (new)"}"
        lines += "Messages: ${session.messageCount}"
        lines += "Engine: ${session.providerId ?: "Unus Lumen"}"
        lines += if (session.isBusy) "Busy: Guru is working on a response." else "Idle."
        return SlashCommandResult.Handled(lines.joinToString("\n"))
    }
}