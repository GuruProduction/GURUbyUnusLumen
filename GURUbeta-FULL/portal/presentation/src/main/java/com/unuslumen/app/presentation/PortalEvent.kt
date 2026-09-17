package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.AiMessageAttachment

sealed interface PortalEvent {
    data class SendMessage(
        val content: String,
        val attachments: List<AiMessageAttachment>
    ): PortalEvent
    data class PortalEventAction(
        val name: String,
        val payload: String
    ): PortalEvent
    data class SearchNotes(val query: String) : PortalEvent
    data class SearchTasks(val query: String) : PortalEvent
    data class AddAttachmentNote(val id: String): PortalEvent
    data class AddAttachmentTask(val id: String): PortalEvent
    data object AddAttachmentEvents: PortalEvent
    data class AddAttachmentFile(val uri: android.net.Uri): PortalEvent
    data class RemoveAttachment(val index: Int): PortalEvent
    data object CancelMessage: PortalEvent
    data object NewConversation: PortalEvent
    data class VisionCommand(val mode: String?) : PortalEvent
}
