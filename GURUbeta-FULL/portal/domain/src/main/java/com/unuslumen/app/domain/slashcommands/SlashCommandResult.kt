package com.unuslumen.app.domain.slashcommands

/**
 * Typed outcome of a completed command. Commands never call the host directly
 * mid-logic; they return a result and the single dispatcher in the action
 * interface applies it. Keeps command classes pure enough to unit test with a
 * fake session and no host at all.
 */
sealed interface SlashCommandResult {
    /** A completed on-device action with an optional confirmation reply. */
    data class Handled(val reply: String? = null) : SlashCommandResult
    /** Show an expandable detail list block in the chat (title plus items). */
    data class DetailList(val title: String, val items: List<String>) : SlashCommandResult
    /** Interrupt the in-flight engine run, then optionally show [reply]. */
    data class Cancelled(val reply: String? = null) : SlashCommandResult
    /** Start a fresh conversation, preserving the current one. */
    data object NewConversation : SlashCommandResult
    /** Navigate to the Luxify skills screen. */
    data object OpenSkills : SlashCommandResult
    /** Show the device permission gate. */
    data object OpenPermissionGate : SlashCommandResult
    /** The command belongs to the engine; deliver [commandText] as a request. */
    data class EngineForward(val commandText: String) : SlashCommandResult
}