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