package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class EmailListResult(val success: Boolean, val emails: List<EmailSummary>, val error: String? = null) : ToolResultData
@Serializable data class EmailSummary(val id: String, val from: String, val to: String, val subject: String, val preview: String, val date: String, val isRead: Boolean, val hasAttachments: Boolean) : ToolResultData
@Serializable data class EmailReadResult(val success: Boolean, val email: EmailDetail? = null, val error: String? = null) : ToolResultData
@Serializable data class EmailDetail(val id: String, val from: String, val to: String, val cc: String? = null, val subject: String, val body: String, val htmlBody: String? = null, val date: String, val attachments: List<EmailAttachment> = emptyList()) : ToolResultData
@Serializable data class EmailAttachment(val filename: String, val mimeType: String, val size: Long) : ToolResultData
@Serializable data class EmailSendResult(val success: Boolean, val emailId: String? = null, val error: String? = null) : ToolResultData
@Serializable data class EmailActionResult(val success: Boolean, val action: String? = null, val error: String? = null) : ToolResultData