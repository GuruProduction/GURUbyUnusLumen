package com.unuslumen.app.domain.slashcommands

import com.unuslumen.app.domain.slashcommands.commands.ClearCommand
import com.unuslumen.app.domain.slashcommands.commands.CommandsCommand
import com.unuslumen.app.domain.slashcommands.commands.HelpCommand
import com.unuslumen.app.domain.slashcommands.commands.StatusCommand
import com.unuslumen.app.domain.slashcommands.commands.StopCommand
import com.unuslumen.app.domain.slashcommands.commands.TasksCommand
import com.unuslumen.app.domain.slashcommands.commands.ThinkCommand
import com.unuslumen.app.domain.slashcommands.commands.ToolsCommand
import com.unuslumen.app.domain.slashcommands.commands.UsageCommand
import com.unuslumen.app.domain.slashcommands.commands.WhoamiCommand
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import org.koin.core.annotation.Single

/**
 * Factory producing the key-to-action map every SlashCommandDispatcher needs.
 * Registered with Koin; each command is one class with one job and adding a
 * command means adding a class and one line here.
 */
@Single
class SlashCommandActionMap(
    getPreference: GetPreferenceUseCase,
    savePreference: SavePreferenceUseCase,
    catalogue: SlashCommandCatalogue,
) {
    val actions: Map<String, SlashCommandAction> = listOf(
        HelpCommand(catalogue::helpText),
        CommandsCommand(catalogue::helpText),
        WhoamiCommand(getPreference),
        StatusCommand(),
        StopCommand(),
        ClearCommand(),
        ThinkCommand(getPreference, savePreference),
        UsageCommand(),
        TasksCommand(),
        ToolsCommand(),
    ).associateBy { it.key }
}

/**
 * The presentation registry hands its command catalogue text in here so domain
 * can render /help without ever importing presentation types.
 */
@Single
class SlashCommandCatalogue {
    var helpTextProvider: (() -> String)? = null
    fun helpText(): String = helpTextProvider?.invoke() ?: "No commands registered."
}

@Single
class SlashCommandDispatcherFactory(
    actionMap: SlashCommandActionMap,
) {
    val dispatcher = SlashCommandDispatcher(actionMap.actions)
}