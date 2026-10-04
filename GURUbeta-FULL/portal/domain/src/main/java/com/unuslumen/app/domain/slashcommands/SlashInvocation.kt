// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.slashcommands

/**
 * Invocation request handed to a command action. Domain owns this shape; the
 * presentation registry (GuruSlashCommand in portal/presentation) fills it in
 * via its own router, keeping the dependency arrow pointing the right way.
 */
data class SlashInvocation(
    /** Registry key, e.g. "help", "think", "config". */
    val key: String,
    /** Raw text after the command name, unmodified. */
    val rawArgs: String?,
    /** Positional values parsed by the registry, in arg-definition order. */
    val values: Map<String, String>,
)