package com.unuslumen.app.domain.slashcommands

/**
 * One executable slash command. Implementations live one per file under
 * commands/local or commands/engine. [key] matches the GuruSlashCommand key in
 * the presentation registry, which stays the single source of truth for names,
 * args and descriptions; this layer owns only execution.
 */
interface SlashCommandAction {
    /** Registry key this action owns, e.g. "help", "think". */
    val key: String
    suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult
}

/**
 * Routes a resolved invocation to its action by key. Unknown keys fall through
 * to the engine, because any command the app has not claimed locally is engine
 * territory by definition. Both chat surfaces call this single dispatcher so
 * behaviour is identical everywhere; screen-only effects (navigation, sheets)
 * ride on Handled results through the host's dedicated methods.
 */
class SlashCommandDispatcher(
    private val actions: Map<String, SlashCommandAction>,
) {
    /** Apply a result to the host. The single place a result becomes a side effect. */
    fun apply(result: SlashCommandResult, host: SlashCommandHost) {
        when (result) {
            is SlashCommandResult.Handled -> result.reply?.let(host::showSystemReply)
            is SlashCommandResult.DetailList -> host.showDetailList(result.title, result.items)
            is SlashCommandResult.Cancelled -> {
                host.cancelRun()
                result.reply?.let(host::showSystemReply)
            }
            is SlashCommandResult.NewConversation -> host.startNewConversation()
            is SlashCommandResult.OpenSkills -> host.openSkills()
            is SlashCommandResult.OpenPermissionGate -> host.openPermissionGate()
            is SlashCommandResult.EngineForward -> host.forwardToEngine(result.commandText)
        }
    }

    suspend fun execute(
        invocation: SlashInvocation,
        session: SlashCommandSession,
    ): SlashCommandResult {
        return try {
            actions[invocation.key]?.execute(invocation, session)
                ?: run {
                    // Unclaimed keys: screen-local navigation commands and the
                    // engine catch-all, both as pure results.
                    when (invocation.key) {
                        "skills" -> SlashCommandResult.OpenSkills
                        "permissions" -> SlashCommandResult.OpenPermissionGate
                        else -> SlashCommandResult.EngineForward(
                            buildEngineText(invocation)
                        )
                    }
                }
        } catch (e: Exception) {
            SlashCommandResult.Handled("Command failed: ${e.message ?: "unknown error"}")
        }
    }

    /** Engine commands always send their full text, raw args first. */
    private fun buildEngineText(invocation: SlashInvocation): String {
        val base = "/${invocation.key}"
        val raw = invocation.rawArgs?.trim().orEmpty()
        return if (raw.isEmpty()) base else "$base $raw"
    }
}