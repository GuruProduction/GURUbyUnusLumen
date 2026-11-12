// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.commands.egress

import com.unuslumen.app.presentation.components.CommandArgChoice
import com.unuslumen.app.presentation.components.CommandArgDefinition
import com.unuslumen.app.presentation.components.CommandArgMenuSpec
import com.unuslumen.app.presentation.components.CommandArgsParsing
import com.unuslumen.app.presentation.components.CommandCategory
import com.unuslumen.app.presentation.components.CommandTier
import com.unuslumen.app.presentation.components.GuruSlashCommand

/**
 * /clearnet on|off|status — the registry entry for the owner's override.
 *
 * THE DESCRIPTION NAMES THE LIMITS, because an override described loosely is worse than no
 * override. It does not touch local traffic, and it does not touch anything carrying a
 * credential, so what it unlocks is public content and nothing else.
 *
 * The behaviour lives in the domain action with the matching key.
 */
val ClearnetCommandEntry: GuruSlashCommand = GuruSlashCommand(
    key = "clearnet",
    name = "clearnet",
    description = "Allow public requests to go direct instead of through Tor. Requests to your " +
        "own network are unaffected, and anything carrying a credential still only rides Tor. " +
        "Turning it off returns to Automatic, which rides Tor and fails with a clear reason " +
        "rather than going direct.",
    acceptsArgs = true,
    args = listOf(
        CommandArgDefinition(
            name = "mode",
            description = "What to do with the clearnet override",
            choices = listOf(
                CommandArgChoice("on", "On"),
                CommandArgChoice("off", "Off"),
                CommandArgChoice("status", "Status"),
            ),
        ),
    ),
    argsParsing = CommandArgsParsing.POSITIONAL,
    argsMenu = CommandArgMenuSpec.Auto,
    category = CommandCategory.NETWORK,
    tier = CommandTier.ESSENTIAL,
)
