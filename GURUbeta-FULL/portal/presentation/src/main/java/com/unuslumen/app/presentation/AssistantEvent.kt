package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.slashcommands.SlashCommandHost
import com.unuslumen.app.presentation.components.CommandArgs
import com.unuslumen.app.presentation.components.GuruSlashCommand

sealed interface AssistantEvent {
    data class SendMessage(
        val content: String,
        val attachments: List<AiMessageAttachment>
    ): AssistantEvent
    data class PortalEvent(
        val name: String,
        val payload: String
    ): AssistantEvent
    data class SearchNotes(val query: String) : AssistantEvent
    data class SearchTasks(val query: String) : AssistantEvent
    data class AddAttachmentNote(val id: String): AssistantEvent
    data class AddAttachmentTask(val id: String): AssistantEvent
    data object AddAttachmentEvents: AssistantEvent
    data class RemoveAttachment(val index: Int): AssistantEvent
    data object CancelMessage: AssistantEvent
    data object NewConversation: AssistantEvent
    data class AddAttachmentFile(val uri: android.net.Uri): AssistantEvent
    data class SlashCommand(
        val command: GuruSlashCommand,
        val args: CommandArgs?,
        val fullText: String,
        /** Nav-capable effects only the screen can perform. */
        val screenHost: SlashCommandHost? = null,
    ): AssistantEvent
}
