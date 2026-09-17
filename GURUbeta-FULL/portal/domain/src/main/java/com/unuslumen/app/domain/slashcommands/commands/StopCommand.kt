package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation

/**
 * /stop — interrupt the in-flight engine run. The command returns a Cancelled
 * result; the dispatcher applies cancellation through the host and shows the
 * confirmation reply, with zero engine round trip.
 */
class StopCommand : SlashCommandAction {
    override val key: String = "stop"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        return SlashCommandResult.Cancelled(reply = "Stopped.")
    }
}