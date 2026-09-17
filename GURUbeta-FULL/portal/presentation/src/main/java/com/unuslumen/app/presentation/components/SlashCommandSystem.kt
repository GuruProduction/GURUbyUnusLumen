package com.unuslumen.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.gradientBrushColor

// ─── Command Arg Types ───

enum class CommandArgType { STRING, NUMBER, BOOLEAN }

data class CommandArgChoice(val value: String, val label: String = value)

data class CommandArgChoiceContext(
    val command: GuruSlashCommand,
    val arg: CommandArgDefinition,
)

data class CommandArgDefinition(
    val name: String,
    val description: String,
    val type: CommandArgType = CommandArgType.STRING,
    val required: Boolean = false,
    val choices: List<CommandArgChoice>? = null,
    val choicesProvider: ((CommandArgChoiceContext) -> List<CommandArgChoice>)? = null,
    val captureRemaining: Boolean = false,
) {
    fun resolveChoices(command: GuruSlashCommand): List<CommandArgChoice> {
        if (choicesProvider != null) {
            return choicesProvider(CommandArgChoiceContext(command, this))
        }
        return choices ?: emptyList()
    }

    fun hasChoices(command: GuruSlashCommand): Boolean {
        return resolveChoices(command).isNotEmpty()
    }
}

// ─── Command Args ───

typealias CommandArgValues = Map<String, String>

data class CommandArgs(
    val raw: String,
    val values: CommandArgValues = emptyMap(),
)

enum class CommandArgsParsing { NONE, POSITIONAL }

// ─── Arg Menu ───

sealed interface CommandArgMenuSpec {
    data object Auto : CommandArgMenuSpec
    data class Manual(val arg: String, val title: String? = null) : CommandArgMenuSpec
}

data class CommandArgMenuResult(
    val arg: CommandArgDefinition,
    val choices: List<CommandArgChoice>,
    val title: String,
)

// ─── Command Categories & Tiers ───

enum class CommandCategory {
    SESSION, OPTIONS, STATUS, MANAGEMENT, MEDIA, TOOLS
}

enum class CommandTier {
    ESSENTIAL, STANDARD, POWER
}

// ─── Command Context ───

data class CommandContext(
    val fullInput: String,
)

// ─── The Main Command Definition ───

data class GuruSlashCommand(
    val key: String,
    val name: String,
    val description: String,
    val aliases: List<String> = emptyList(),
    val acceptsArgs: Boolean = false,
    val args: List<CommandArgDefinition> = emptyList(),
    val argsParsing: CommandArgsParsing = CommandArgsParsing.NONE,
    val argsMenu: CommandArgMenuSpec? = null,
    val category: CommandCategory = CommandCategory.TOOLS,
    val tier: CommandTier = CommandTier.STANDARD,
    val handler: ((CommandArgs, CommandContext) -> Unit)? = null,
) {
    val displayName: String get() = "/$name"

    val allNames: List<String> get() = listOf(name) + aliases

    fun matchesPrefix(input: String): Boolean {
        val trimmed = input.trim().removePrefix("/")
        if (trimmed.isEmpty()) return true
        return allNames.any { it.startsWith(trimmed, ignoreCase = true) }
    }
}

// ─── Registry ───

object GuruSlashCommandRegistry {
    private val _commands = mutableListOf<GuruSlashCommand>()
    val commands: List<GuruSlashCommand> get() = _commands.toList()

    fun register(command: GuruSlashCommand) {
        _commands.add(command)
    }

    fun registerAll(vararg commands: GuruSlashCommand) {
        _commands.addAll(commands)
    }

    fun unregister(key: String) {
        _commands.removeAll { it.key == key }
    }

    fun clear() {
        _commands.clear()
    }

    fun findByKey(key: String): GuruSlashCommand? {
        return _commands.find { it.key == key }
    }

    /**
     * Find commands matching the current input text.
     * Returns all commands if input is just "/", otherwise filters by prefix match.
     */
    fun match(input: String): List<GuruSlashCommand> {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return emptyList()
        return _commands.filter { it.matchesPrefix(trimmed) }
    }

    /**
     * Resolve a full input string to a command.
     * "/clear" resolves to the clear command. "/clear extra" also resolves to clear.
     */
    fun resolve(input: String): GuruSlashCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null
        val parts = trimmed.split(" ", limit = 2)
        val commandPart = parts[0]
        return _commands.find { cmd ->
            cmd.allNames.any { commandPart.equals("/$it", ignoreCase = true) }
        }
    }

    /**
     * Parse positional args from raw input text.
     * Strips the command name, splits remaining by whitespace, assigns to arg definitions in order.
     */
    fun parseArgs(command: GuruSlashCommand, rawInput: String): CommandArgs? {
        if (command.argsParsing == CommandArgsParsing.NONE || command.args.isEmpty()) {
            val trimmed = rawInput.trim()
            return if (trimmed.isNotEmpty()) CommandArgs(raw = trimmed) else null
        }

        val trimmed = rawInput.trim()
        val parts = trimmed.split(" ", limit = 2)
        val argText = if (parts.size > 1) parts[1].trim() else ""

        if (argText.isEmpty() && command.args.any { it.required }) {
            return null
        }

        if (argText.isEmpty()) {
            return CommandArgs(raw = trimmed)
        }

        val tokens = argText.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val values = mutableMapOf<String, String>()
        var index = 0

        for (arg in command.args) {
            if (index >= tokens.size) break
            if (arg.captureRemaining) {
                values[arg.name] = tokens.drop(index).joinToString(" ")
                break
            }
            values[arg.name] = tokens[index]
            index++
        }

        return CommandArgs(raw = trimmed, values = values)
    }

    /**
     * Check if a command needs an arg menu shown to the user.
     * Returns null if no menu needed, or the menu spec if the user should pick an arg.
     */
    fun needsArgMenu(resources: android.content.res.Resources, command: GuruSlashCommand, args: CommandArgs?): CommandArgMenuResult? {
        if (command.args.isEmpty()) return null
        val menu = command.argsMenu ?: return null

        // If values were already parsed and all required args are filled, no menu
        if (args?.values != null && args.values.isNotEmpty()) {
            val missingRequired = command.args.filter { it.required && args.values[it.name] == null }
            if (missingRequired.isEmpty()) return null
        }

        // If raw text was provided but argsParsing is none, skip menu (raw text IS the arg)
        if (args?.raw != null && args.values.isEmpty() && command.argsParsing == CommandArgsParsing.NONE) {
            return null
        }

        // Find the target arg
        val targetArg: CommandArgDefinition = when (menu) {
            is CommandArgMenuSpec.Auto -> {
                command.args.firstOrNull { arg ->
                    arg.hasChoices(command) && (args?.values?.get(arg.name) == null)
                } ?: return null
            }
            is CommandArgMenuSpec.Manual -> {
                command.args.find { it.name == menu.arg } ?: return null
            }
        }

        val choices = targetArg.resolveChoices(command)
        if (choices.isEmpty()) return null

        val title = when (menu) {
            is CommandArgMenuSpec.Manual -> menu.title ?: resources.getString(
                R.string.slash_command_arg_menu_choose_title,
                targetArg.description,
                command.name
            )
            is CommandArgMenuSpec.Auto -> {
                val options = choices.joinToString(", ") { it.label }
                if (options.length <= 160) {
                    resources.getString(
                        R.string.slash_command_arg_menu_choose_with_options_title,
                        targetArg.name,
                        command.name,
                        options
                    )
                } else {
                    resources.getString(
                        R.string.slash_command_arg_menu_choose_title,
                        targetArg.name,
                        command.name
                    )
                }
            }
        }

        return CommandArgMenuResult(arg = targetArg, choices = choices, title = title)
    }

    /**
     * Build the full command text from parsed args for sending to AI.
     */
    fun buildCommandText(command: GuruSlashCommand, args: CommandArgs?): String {
        if (args == null) return "/${command.name}"
        val raw = args.raw.trim()
        if (raw.isNotEmpty()) return raw

        val positionalParts = command.args.mapNotNull { arg ->
            args.values[arg.name]
        }
        return if (positionalParts.isNotEmpty()) {
            "/${command.name} ${positionalParts.joinToString(" ")}"
        } else {
            "/${command.name}"
        }
    }

    // ─── Default Commands ───

    init {
        registerAll(
            GuruSlashCommand(
                key = "clear",
                name = "clear",
                description = "Clear the current conversation and start fresh.",
                aliases = listOf("new", "reset"),
                category = CommandCategory.SESSION,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "help",
                name = "help",
                description = "Show available commands and what they do.",
                category = CommandCategory.STATUS,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "commands",
                name = "commands",
                description = "List all slash commands.",
                category = CommandCategory.STATUS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "think",
                name = "think",
                description = "Set thinking level for the AI.",
                aliases = listOf("t", "thinking"),
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "level",
                        description = "Thinking level",
                        choices = listOf(
                            CommandArgChoice("off", "Off"),
                            CommandArgChoice("low", "Low"),
                            CommandArgChoice("medium", "Medium"),
                            CommandArgChoice("high", "High"),
                            CommandArgChoice("xhigh", "X-High"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.OPTIONS,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "model",
                name = "model",
                description = "Show or set the AI model.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "model",
                        description = "Model name",
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                category = CommandCategory.OPTIONS,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "models",
                name = "models",
                description = "List available AI models.",
                category = CommandCategory.OPTIONS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "verbose",
                name = "verbose",
                description = "Toggle verbose mode.",
                aliases = listOf("v"),
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "mode",
                        description = "Verbose mode",
                        choices = listOf(
                            CommandArgChoice("on", "On"),
                            CommandArgChoice("off", "Off"),
                            CommandArgChoice("full", "Full"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.OPTIONS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "status",
                name = "status",
                description = "Show current session status.",
                category = CommandCategory.STATUS,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "stop",
                name = "stop",
                description = "Stop the current AI run.",
                category = CommandCategory.SESSION,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "restart",
                name = "restart",
                description = "Restart the AI session.",
                category = CommandCategory.TOOLS,
                tier = CommandTier.POWER,
            ),
            GuruSlashCommand(
                key = "export",
                name = "export",
                description = "Export current session.",
                aliases = listOf("export-session"),
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "path",
                        description = "Output path (optional)",
                        required = false,
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                category = CommandCategory.STATUS,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "usage",
                name = "usage",
                description = "Show usage or cost summary.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "mode",
                        description = "Usage display mode",
                        choices = listOf(
                            CommandArgChoice("off", "Off"),
                            CommandArgChoice("tokens", "Tokens"),
                            CommandArgChoice("full", "Full"),
                            CommandArgChoice("cost", "Cost"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.OPTIONS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "compact",
                name = "compact",
                description = "Compact the session context.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "instructions",
                        description = "Extra compaction instructions",
                        captureRemaining = true,
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                category = CommandCategory.SESSION,
                tier = CommandTier.ESSENTIAL,
            ),
            GuruSlashCommand(
                key = "fast",
                name = "fast",
                description = "Toggle fast mode.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "mode",
                        description = "Fast mode",
                        choices = listOf(
                            CommandArgChoice("status", "Status"),
                            CommandArgChoice("on", "On"),
                            CommandArgChoice("off", "Off"),
                            CommandArgChoice("default", "Default"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.OPTIONS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "config",
                name = "config",
                description = "Show or set config values.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "action",
                        description = "show, get, set, or unset",
                        choices = listOf(
                            CommandArgChoice("show", "Show"),
                            CommandArgChoice("get", "Get"),
                            CommandArgChoice("set", "Set"),
                            CommandArgChoice("unset", "Unset"),
                        ),
                    ),
                    CommandArgDefinition(
                        name = "path",
                        description = "Config path",
                    ),
                    CommandArgDefinition(
                        name = "value",
                        description = "Value for set",
                        captureRemaining = true,
                    ),
                ),
                argsParsing = CommandArgsParsing.NONE,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.MANAGEMENT,
                tier = CommandTier.POWER,
            ),
            GuruSlashCommand(
                key = "debug",
                name = "debug",
                description = "Set runtime debug overrides.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "action",
                        description = "show, reset, set, or unset",
                        choices = listOf(
                            CommandArgChoice("show", "Show"),
                            CommandArgChoice("reset", "Reset"),
                            CommandArgChoice("set", "Set"),
                            CommandArgChoice("unset", "Unset"),
                        ),
                    ),
                    CommandArgDefinition(
                        name = "path",
                        description = "Debug path",
                    ),
                    CommandArgDefinition(
                        name = "value",
                        description = "Value for set",
                        captureRemaining = true,
                    ),
                ),
                argsParsing = CommandArgsParsing.NONE,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.MANAGEMENT,
                tier = CommandTier.POWER,
            ),
            GuruSlashCommand(
                key = "session",
                name = "session",
                description = "Manage session-level settings.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "action",
                        description = "idle or max-age",
                        choices = listOf(
                            CommandArgChoice("idle", "Idle"),
                            CommandArgChoice("max-age", "Max Age"),
                        ),
                    ),
                    CommandArgDefinition(
                        name = "value",
                        description = "Duration (24h, 90m) or off",
                        captureRemaining = true,
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.SESSION,
                tier = CommandTier.POWER,
            ),
            GuruSlashCommand(
                key = "goal",
                name = "goal",
                description = "Show or control the current goal.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "action",
                        description = "status, start, pause, resume, complete, block, clear",
                        choices = listOf(
                            CommandArgChoice("status", "Status"),
                            CommandArgChoice("start", "Start"),
                            CommandArgChoice("pause", "Pause"),
                            CommandArgChoice("resume", "Resume"),
                            CommandArgChoice("complete", "Complete"),
                            CommandArgChoice("block", "Block"),
                            CommandArgChoice("clear", "Clear"),
                        ),
                    ),
                    CommandArgDefinition(
                        name = "text",
                        description = "Goal objective or note",
                        captureRemaining = true,
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.STATUS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "whoami",
                name = "whoami",
                description = "Show your sender id.",
                aliases = listOf("id"),
                category = CommandCategory.STATUS,
                tier = CommandTier.POWER,
            ),
            GuruSlashCommand(
                key = "tasks",
                name = "tasks",
                description = "List background tasks for this session.",
                category = CommandCategory.STATUS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "tools",
                name = "tools",
                description = "List available runtime tools.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "mode",
                        description = "compact or verbose",
                        choices = listOf(
                            CommandArgChoice("compact", "Compact"),
                            CommandArgChoice("verbose", "Verbose"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.STATUS,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "activation",
                name = "activation",
                description = "Set group activation mode.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "mode",
                        description = "mention or always",
                        choices = listOf(
                            CommandArgChoice("mention", "Mention"),
                            CommandArgChoice("always", "Always"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.MANAGEMENT,
                tier = CommandTier.POWER,
            ),
            GuruSlashCommand(
                key = "send",
                name = "send",
                description = "Set send policy.",
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "mode",
                        description = "on, off, or inherit",
                        choices = listOf(
                            CommandArgChoice("on", "On"),
                            CommandArgChoice("off", "Off"),
                            CommandArgChoice("inherit", "Inherit"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.MANAGEMENT,
                tier = CommandTier.POWER,
            ),
            GuruSlashCommand(
                key = "skills",
                name = "skills",
                description = "Open the Skills screen to manage your Luxify skills.",
                category = CommandCategory.MANAGEMENT,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "permissions",
                name = "permissions",
                description = "Open the permission gate to review and grant device permissions.",
                category = CommandCategory.MANAGEMENT,
                tier = CommandTier.STANDARD,
            ),
            GuruSlashCommand(
                key = "vision",
                name = "vision",
                description = "Turn screen vision on or off — Guru sees your screen with every message.",
                aliases = listOf("eyes", "sight"),
                acceptsArgs = true,
                args = listOf(
                    CommandArgDefinition(
                        name = "mode",
                        description = "Vision mode",
                        choices = listOf(
                            CommandArgChoice("on", "On"),
                            CommandArgChoice("off", "Off"),
                            CommandArgChoice("status", "Status"),
                        ),
                    ),
                ),
                argsParsing = CommandArgsParsing.POSITIONAL,
                argsMenu = CommandArgMenuSpec.Auto,
                category = CommandCategory.OPTIONS,
                tier = CommandTier.ESSENTIAL,
            ),
        )
    }
}

// ─── UI: Slash Command Overlay ───

// Brand tokens — same warm ink / gold palette PortalScreen paints its background with.
private val SlashInk = Color(0xFF2B241C)
private val SlashPaperHigh = Color(0xFFF0E8DA)
private val SlashPaperLow = Color(0xFFE4D9C6)
private val SlashGold = Color(0xFFDAA520)

// Cap the menu so a broad prefix (or bare "/") scrolls instead of filling the screen.
private val SlashMenuMaxHeight = 280.dp

/**
 * One command row. Gold monospace key on the left (the app's slash signature),
 * description beside it, subtle divider between rows.
 */
@Composable
private fun SlashCommandRow(
    displayName: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            ),
            color = SlashGold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = SlashInk.copy(alpha = 0.72f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shared glass-lite surface for both slash menus. Warm paper wash with a hairline
 * gold border and the gold gradient drop shadow the lobby bars use, so the menu
 * reads as part of the portal instead of a system popup.
 */
@Composable
private fun SlashMenuSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(shape) {
                offset = Offset(0f, 24f)
                alpha = 0.22f
                radius = 30f
                brush = gradientBrushColor()
            },
    ) {
        Box {
            // Warm paper wash behind the (optionally glass) surface — same vertical
            // cream fade PortalScreen's background overlay uses.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                SlashPaperHigh.copy(alpha = 0.92f),
                                SlashPaperLow.copy(alpha = 0.88f),
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlashGold.copy(alpha = 0.35f), shape)
            ) {
                content()
            }
        }
    }
}

@Composable
fun SlashCommandOverlay(
    text: String,
    onCommandSelected: (GuruSlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matchingCommands by remember(text) {
        derivedStateOf { GuruSlashCommandRegistry.match(text) }
    }
    val showOverlay = text.trimStart().startsWith("/") && matchingCommands.isNotEmpty()

    AnimatedVisibility(
        visible = showOverlay,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier,
    ) {
        SlashMenuSurface(
            modifier = Modifier.heightIn(max = SlashMenuMaxHeight),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
            ) {
                matchingCommands.forEachIndexed { index, command ->
                    if (index > 0) {
                        HorizontalDivider(color = SlashInk.copy(alpha = 0.12f))
                    }
                    SlashCommandRow(
                        displayName = command.displayName,
                        description = command.description,
                        onClick = { onCommandSelected(command) },
                    )
                }
            }
        }
    }
}

// ─── UI: Arg Menu Overlay ───

@Composable
fun SlashCommandArgMenu(
    menu: CommandArgMenuResult,
    onArgSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SlashMenuSurface(
        modifier = modifier.heightIn(max = SlashMenuMaxHeight),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = menu.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 0.6.sp,
                ),
                color = SlashInk.copy(alpha = 0.65f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            HorizontalDivider(color = SlashInk.copy(alpha = 0.12f))
            menu.choices.forEachIndexed { index, choice ->
                if (index > 0) {
                    HorizontalDivider(color = SlashInk.copy(alpha = 0.08f))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArgSelected(choice.value) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = SlashInk,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = choice.value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = SlashGold.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
