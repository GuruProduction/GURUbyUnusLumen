// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
