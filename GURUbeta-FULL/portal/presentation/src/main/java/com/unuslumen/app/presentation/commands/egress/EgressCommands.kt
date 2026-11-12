// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.commands.egress

import com.unuslumen.app.presentation.components.GuruSlashCommand

/**
 * The egress command group, assembled in one place.
 *
 * WHY THIS EXISTS AS A SEPARATE GROUP. The registry's own `init` block is already twenty-odd
 * entries long, and adding to it is how it became a file nobody reads. A group lets a family
 * of commands be defined together and registered with one call, so the registry keeps doing
 * lookup and nothing else.
 *
 * A group is a list, not a class, because a command is a value. Registration is the only
 * verb, and it happens once, in one place, at startup.
 */
object EgressCommands {

    /** Every command this group contributes, in menu order. */
    val all: List<GuruSlashCommand> = listOf(
        TorCommandEntry,
        ClearnetCommandEntry,
    )
}
