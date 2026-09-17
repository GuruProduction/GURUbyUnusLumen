package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation

/**
 * /tools — report which runtime tools this conversation has actually executed.
 * Verbose adds failed flags and recency to each row. With no history the reply
 * says so instead of listing an empty wall.
 */
class ToolsCommand : SlashCommandAction {
    override val key: String = "tools"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        val mode = (invocation.values["mode"] ?: invocation.rawArgs)?.lowercase()?.trim()
        val verbose = mode == "verbose"
        if (session.recentToolCalls.isEmpty()) {
            return SlashCommandResult.Handled(
                "No tools have run in this conversation yet. When Guru calls a tool it lands here."
            )
        }
        // Distinct tools in first-seen order, with usage counts.
        val counts = session.recentToolCalls
            .asReversed()
            .groupingBy { it.name }
            .eachCount()
        val failedNames = session.recentToolCalls.filter { it.failed }.map { it.name }.toSet()
        val lines = counts.entries.map { (name, count) ->
            val failNote = if (verbose && name in failedNames) " (with failures)" else ""
            "$name${if (counts[name] == 1) "" else " x${counts[name]}"}$failNote"
        }
        return SlashCommandResult.DetailList(
            title = "Runtime tools used in this conversation",
            items = lines,
        )
    }
}