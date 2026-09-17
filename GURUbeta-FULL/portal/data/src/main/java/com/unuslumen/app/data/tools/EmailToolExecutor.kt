package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class EmailToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolName) {
            EmailToolDefinitions.EMAIL_LIST -> { val r = EmailListResult(false, emptyList(), "Email listing requires IMAP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailListResult.serializer(), r)) }
            EmailToolDefinitions.EMAIL_READ -> { val r = EmailReadResult(false, null, "Email reading requires IMAP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailReadResult.serializer(), r)) }
            EmailToolDefinitions.EMAIL_SEARCH -> { val r = EmailListResult(false, emptyList(), "Email search requires IMAP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailListResult.serializer(), r)) }
            EmailToolDefinitions.EMAIL_SEND -> { val r = EmailSendResult(false, null, "Email sending requires SMTP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailSendResult.serializer(), r)) }
            EmailToolDefinitions.EMAIL_REPLY -> { val r = EmailSendResult(false, null, "Email reply requires IMAP/SMTP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailSendResult.serializer(), r)) }
            EmailToolDefinitions.EMAIL_FORWARD -> { val r = EmailSendResult(false, null, "Email forward requires IMAP/SMTP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailSendResult.serializer(), r)) }
            EmailToolDefinitions.EMAIL_MOVE -> { val r = EmailActionResult(false, null, "Email move requires IMAP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailActionResult.serializer(), r)) }
            EmailToolDefinitions.EMAIL_DELETE -> { val r = EmailActionResult(false, null, "Email delete requires IMAP configuration."); ToolExecutionResult.success(r, json.encodeToString(EmailActionResult.serializer(), r)) }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}