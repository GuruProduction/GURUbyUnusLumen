package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object NoteToSelfToolDefinitions : ToolSetRegistration {

    const val CREATE_NOTE_TO_SELF = "createNoteToSelf"
    const val LIST_NOTES_TO_SELF = "listNotesToSelf"
    const val UPDATE_NOTE_TO_SELF = "updateNoteToSelf"
    const val DELETE_NOTE_TO_SELF = "deleteNoteToSelf"
    const val ENABLE_NOTE_TO_SELF = "enableNoteToSelf"
    const val DISABLE_NOTE_TO_SELF = "disableNoteToSelf"

    override val definitions = listOf(
        ToolDefinition(
            name = CREATE_NOTE_TO_SELF,
            description = "Write a note to self: a private reminder that gets injected into your own context whenever the user's message matches your chosen trigger keywords. Use it when you want to preload future behaviour: remembering a name or preference without storing a fact, steering a topic, or reminding yourself of a plan the moment the right words come up. The note lives on-device, permanently, and fires automatically. Example: content 'Sam is the person the human went on a date with in Manchester. Be curious about how it went. Do not ask again about details they already told you on 2026-09-14.' with keywords 'sam, manchester, the date'.",
            category = "meta",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Short name for the note, e.g. 'sam_manchester_date'. Unique, lowercase letters/numbers/underscores. Re-saving with the same name updates the existing note."),
                ToolParameter("content", ToolParameterType.String, true, "The full reminder text injected into your context when a trigger fires. Write it as a note to yourself, with full context so it makes sense months later. State facts plainly, include dates where relevant."),
                ToolParameter("keywords", ToolParameterType.String, true, "Comma-separated trigger keywords or phrases, matched case-insensitively as whole words/phrases in the user's message. Example: 'sam, manchester, the date'. Use words or phrases the user will actually say. Empty keywords means the note never auto-fires.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = LIST_NOTES_TO_SELF,
            description = "List all notes to self you have written, with their keywords, fire counts, and enabled state. Use this to review what triggers you have set before adding more, or to tidy up stale ones.",
            category = "meta",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = UPDATE_NOTE_TO_SELF,
            description = "Update an existing note to self. Provide the note's name (or ID) plus any fields you want to change. Unspecified fields keep their current values.",
            category = "meta",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Name (or ID) of the note to update, exactly as it was created."),
                ToolParameter("newName", ToolParameterType.String, false, "New name, if renaming."),
                ToolParameter("content", ToolParameterType.String, false, "New reminder text."),
                ToolParameter("keywords", ToolParameterType.String, false, "New comma-separated trigger keywords. This replaces the whole list.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DELETE_NOTE_TO_SELF,
            description = "Permanently delete a note to self by name (or ID). Use when a reminder is wrong or no longer wanted. Prefer disableNoteToSelf when you might want it back.",
            category = "meta",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Name (or ID) of the note to delete.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = ENABLE_NOTE_TO_SELF,
            description = "Re-enable a disabled note to self so its keywords trigger again.",
            category = "meta",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Name (or ID) of the note.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DISABLE_NOTE_TO_SELF,
            description = "Temporarily disable a note to self. It stays stored but its keywords stop firing. Re-enable any time with enableNoteToSelf.",
            category = "meta",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Name (or ID) of the note.")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = NoteToSelfToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}