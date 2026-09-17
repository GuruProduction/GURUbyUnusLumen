package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation

/**
 * /usage — report the estimated context usage of this conversation. With no
 * arg it shows the estimate against the conversation window; "tokens" gives
 * the raw figure, "off" reminds how to suppress usage display in replies.
 * Mirrors the token estimate the data layer feeds its auto-compact trigger
 * (chars / 4 padded 4/3), computed by the session snapshot.
 */
class UsageCommand : SlashCommandAction {
    override val key: String = "usage"

    override suspend fun execute(invocation: SlashInvocation, session: SlashCommandSession): SlashCommandResult {
        val mode = (invocation.values["mode"] ?: invocation.rawArgs)?.lowercase()?.trim()
        val window = CONTEXT_WINDOW_TOKENS
        return when (mode) {
            // Honest answer: nothing in the app prints per-message usage, so
            // there is genuinely nothing to switch off. Pretending otherwise
            // would be a fake setting.
            "off" -> SlashCommandResult.Handled(
                "The app never prints per-message usage, so there's nothing to switch off. " +
                    "Cost reporting runs server-side."
            )
            "tokens" -> SlashCommandResult.Handled(
                "Context usage: ~${session.tokenEstimate} tokens of ${window / 1000}K window " +
                    "(${formatPercent(session.tokenEstimate)} full)."
            )
            "cost" -> SlashCommandResult.Handled(
                "Cost accounting runs server-side. Ask Guru \"what did this conversation cost?\" " +
                    "and he will answer from his usage feed."
            )
            else -> {
                val windowBar = renderBar(session.tokenEstimate, window)
                SlashCommandResult.Handled(
                    "Context usage: ~${session.tokenEstimate} tokens of ${window / 1000}K " +
                        "(${formatPercent(session.tokenEstimate)})\n$windowBar"
                )
            }
        }
    }

    private fun formatPercent(used: Int): String {
        val pct = if (CONTEXT_WINDOW_TOKENS <= 0) 0 else (used * 100L / CONTEXT_WINDOW_TOKENS).toInt()
        return "$pct%"
    }

    /** Ten-slot bar meter, one cell per 10% of the window. */
    private fun renderBar(used: Int, window: Int): String {
        val cells = 10
        val filled = (used.toDouble() / window * cells).toInt().coerceIn(0, cells)
        return buildString {
            append('[')
            repeat(filled) { append('#') }
            repeat(cells - filled) { append('·') }
            append(']')
        }
    }

    private companion object {
        const val CONTEXT_WINDOW_TOKENS = 1_000_000
    }
}