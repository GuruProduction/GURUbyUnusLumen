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
 * /tor on|off|status — the registry entry for the Tor lock.
 *
 * THE DESCRIPTION IS THE CONTRACT, because "tor on" reads like a daemon switch and is not
 * one. Tor is a persistent foreground service with nothing for a command to start; what this
 * sets is the posture, and the posture is what refuses the clearnet override.
 *
 * The behaviour lives in the domain action with the matching key, which writes the same
 * preference every outbound path reads.
 */
val TorCommandEntry: GuruSlashCommand = GuruSlashCommand(
    key = "tor",
    name = "tor",
    description = "Lock public traffic onto Tor, or release the lock. On refuses the clearnet " +
        "override, so no later command can widen egress. Status reports the current posture. " +
        "Requests to your own network always go direct and are unaffected.",
    acceptsArgs = true,
    args = listOf(
        CommandArgDefinition(
            name = "mode",
            description = "What to do with the Tor lock",
            choices = listOf(
                CommandArgChoice("on", "Lock on"),
                CommandArgChoice("off", "Release"),
                CommandArgChoice("status", "Status"),
            ),
        ),
    ),
    argsParsing = CommandArgsParsing.POSITIONAL,
    argsMenu = CommandArgMenuSpec.Auto,
    category = CommandCategory.NETWORK,
    tier = CommandTier.ESSENTIAL,
)
