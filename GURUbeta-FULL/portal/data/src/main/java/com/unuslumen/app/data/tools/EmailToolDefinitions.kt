package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object EmailToolDefinitions : ToolSetRegistration {
    const val EMAIL_LIST = "emailList"
    const val EMAIL_READ = "emailRead"
    const val EMAIL_SEARCH = "emailSearch"
    const val EMAIL_SEND = "emailSend"
    const val EMAIL_REPLY = "emailReply"
    const val EMAIL_FORWARD = "emailForward"
    const val EMAIL_MOVE = "emailMove"
    const val EMAIL_DELETE = "emailDelete"

    override val definitions = listOf(
        ToolDefinition(name = EMAIL_LIST, description = "List emails in a folder.", category = "email", parameters = listOf(ToolParameter("folder", ToolParameterType.String, false, "Folder name (inbox, sent, drafts, etc.)"), ToolParameter("limit", ToolParameterType.Integer, false, "Number of emails to retrieve"), ToolParameter("offset", ToolParameterType.Integer, false, "Offset for pagination")), permissions = emptyList()),
        ToolDefinition(name = EMAIL_READ, description = "Read the content of an email.", category = "email", parameters = listOf(ToolParameter("emailId", ToolParameterType.String, true, "Email ID")), permissions = emptyList()),
        ToolDefinition(name = EMAIL_SEARCH, description = "Search emails matching a query.", category = "email", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query"), ToolParameter("folder", ToolParameterType.String, false, "Folder to search in"), ToolParameter("limit", ToolParameterType.Integer, false, "Limit results")), permissions = emptyList()),
        ToolDefinition(name = EMAIL_SEND, description = "Send an email.", category = "email", parameters = listOf(ToolParameter("to", ToolParameterType.String, true, "Recipient email address"), ToolParameter("subject", ToolParameterType.String, true, "Email subject"), ToolParameter("body", ToolParameterType.String, true, "Email body"), ToolParameter("cc", ToolParameterType.String, false, "CC recipients (comma-separated)"), ToolParameter("bcc", ToolParameterType.String, false, "BCC recipients (comma-separated)")), permissions = emptyList()),
        ToolDefinition(name = EMAIL_REPLY, description = "Reply to an email.", category = "email", parameters = listOf(ToolParameter("emailId", ToolParameterType.String, true, "Email ID to reply to"), ToolParameter("body", ToolParameterType.String, true, "Reply body"), ToolParameter("replyAll", ToolParameterType.Boolean, false, "Reply all")), permissions = emptyList()),
        ToolDefinition(name = EMAIL_FORWARD, description = "Forward an email.", category = "email", parameters = listOf(ToolParameter("emailId", ToolParameterType.String, true, "Email ID to forward"), ToolParameter("to", ToolParameterType.String, true, "Recipient"), ToolParameter("message", ToolParameterType.String, false, "Additional message")), permissions = emptyList()),
        ToolDefinition(name = EMAIL_MOVE, description = "Move an email to a folder.", category = "email", parameters = listOf(ToolParameter("emailId", ToolParameterType.String, true, "Email ID"), ToolParameter("folder", ToolParameterType.String, true, "Target folder")), permissions = emptyList()),
        ToolDefinition(name = EMAIL_DELETE, description = "Delete an email.", category = "email", parameters = listOf(ToolParameter("emailId", ToolParameterType.String, true, "Email ID"), ToolParameter("permanent", ToolParameterType.Boolean, false, "Permanent delete (bypass trash)")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = EmailToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}