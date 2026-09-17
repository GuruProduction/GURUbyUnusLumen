package com.unuslumen.app.presentation.slashcommands

import com.unuslumen.app.domain.slashcommands.SlashCommandCatalogue
import com.unuslumen.app.domain.slashcommands.SlashCommandDispatcher
import com.unuslumen.app.domain.slashcommands.SlashCommandDispatcherFactory
import com.unuslumen.app.domain.slashcommands.SlashCommandHost
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation
import com.unuslumen.app.presentation.components.CommandArgs
import com.unuslumen.app.presentation.components.GuruSlashCommand
import com.unuslumen.app.presentation.components.GuruSlashCommandRegistry
import org.koin.core.annotation.Single

/**
 * Presentation-side router. Translates registry commands and parsed args into
 * domain invocations, runs the dispatcher, and applies results to a host built
 * by the calling screen/ViewModel. This is the ONLY place presentation types
 * (GuruSlashCommand, CommandArgs) meet the domain slash command layer.
 */
@Single
class ChatHostRouter(
    // The factory is the Koin-registered singleton; the dispatcher itself has
    // no Koin binding of its own, it rides the factory.
    private val dispatcherFactory: SlashCommandDispatcherFactory,
    private val catalogue: SlashCommandCatalogue,
) {
    private val dispatcher: SlashCommandDispatcher get() = dispatcherFactory.dispatcher

    init {
        // Domain renders /help from this catalogue without importing presentation.
        catalogue.helpTextProvider = ::registryCatalogueText
    }

    suspend fun route(
        command: GuruSlashCommand,
        args: CommandArgs?,
        session: SlashCommandSession,
        host: SlashCommandHost,
    ): SlashCommandResult {
        val invocation = SlashInvocation(
            key = command.key,
            rawArgs = args?.raw?.substringAfter(' ', missingDelimiterValue = "")?.trim()?.takeIf { it.isNotEmpty() },
            values = args?.values.orEmpty(),
        )
        // Compute the result, then immediately apply it to the host. A result
        // that never reaches apply() is a dropped command; route() guarantees
        // that cannot happen for any caller.
        val result = dispatcher.execute(invocation, session)
        dispatcher.apply(result, host)
        return result
    }

    private fun registryCatalogueText(): String {
        val builder = StringBuilder("Available commands:")
        val byCategory = GuruSlashCommandRegistry.commands.groupBy { it.category }
        for ((category, commands) in byCategory) {
            builder.appendLine()
            builder.appendLine(category.name.lowercase().replaceFirstChar { it.uppercase() })
            commands.sortedBy { it.tier.ordinal }.forEach { cmd ->
                val aliases = if (cmd.aliases.isNotEmpty()) " (${cmd.aliases.joinToString(", ") { "/$it" }})" else ""
                builder.appendLine("  ${cmd.displayName}$aliases  —  ${cmd.description}")
            }
        }
        return builder.toString().trimEnd()
    }
}