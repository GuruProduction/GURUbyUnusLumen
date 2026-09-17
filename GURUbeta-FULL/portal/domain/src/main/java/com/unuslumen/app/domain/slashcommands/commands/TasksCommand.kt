package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation

/**
 * /tasks — list the user-visible tasks this conversation has surfaced from
 * tool results, exactly what the ActiveTasks popup shows. Opens as a detail
 * list block; no engine round trip.
 */
class TasksCommand : SlashCommandAction {
    override val key: String = "tasks"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        val open = session.conversationTasks.filterNot { it.isCompleted }
        if (open.isEmpty()) {
            return SlashCommandResult.Handled(
                "No open tasks captured in this conversation. Ask Guru to create tasks and they appear here."
            )
        }
        val lines = open.map { task ->
            "${task.title}"
        }
        return SlashCommandResult.DetailList(
            title = "Open tasks in this conversation",
            items = lines,
        )
    }
}