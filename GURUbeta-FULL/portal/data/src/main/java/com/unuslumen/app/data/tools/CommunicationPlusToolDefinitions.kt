package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object CommunicationPlusToolDefinitions : ToolSetRegistration {
    const val DISCORD_SEND = "discordSend"
    const val DISCORD_READ = "discordRead"
    const val DISCORD_REACT = "discordReact"
    const val SLACK_SEND = "slackSend"
    const val SLACK_READ = "slackRead"
    const val SLACK_PIN = "slackPin"
    const val WHATSAPP_SEND = "whatsappSend"
    const val WHATSAPP_SEARCH = "whatsappSearch"
    const val X_POST = "xPost"
    const val X_REPLY = "xReply"
    const val X_DM = "xDm"
    const val X_SEARCH = "xSearch"
    const val VOICE_CALL_START = "voiceCallStart"
    const val VOICE_CALL_STATUS = "voiceCallStatus"

    override val definitions = listOf(
        ToolDefinition(name = DISCORD_SEND, description = "Send a message to a Discord channel.", category = "communication", parameters = listOf(ToolParameter("channel", ToolParameterType.String, true, "Discord channel ID"), ToolParameter("message", ToolParameterType.String, true, "Message content")), permissions = emptyList()),
        ToolDefinition(name = DISCORD_READ, description = "Read recent messages from a Discord channel.", category = "communication", parameters = listOf(ToolParameter("channel", ToolParameterType.String, true, "Discord channel ID"), ToolParameter("limit", ToolParameterType.Integer, false, "Number of messages to read")), permissions = emptyList()),
        ToolDefinition(name = DISCORD_REACT, description = "Add a reaction to a Discord message.", category = "communication", parameters = listOf(ToolParameter("channel", ToolParameterType.String, true, "Discord channel ID"), ToolParameter("messageId", ToolParameterType.String, true, "Message ID"), ToolParameter("emoji", ToolParameterType.String, true, "Emoji reaction")), permissions = emptyList()),
        ToolDefinition(name = SLACK_SEND, description = "Send a message to a Slack channel.", category = "communication", parameters = listOf(ToolParameter("channel", ToolParameterType.String, true, "Slack channel ID"), ToolParameter("message", ToolParameterType.String, true, "Message content")), permissions = emptyList()),
        ToolDefinition(name = SLACK_READ, description = "Read recent messages from a Slack channel.", category = "communication", parameters = listOf(ToolParameter("channel", ToolParameterType.String, true, "Slack channel ID"), ToolParameter("limit", ToolParameterType.Integer, false, "Number of messages to read")), permissions = emptyList()),
        ToolDefinition(name = SLACK_PIN, description = "Pin a message in a Slack channel.", category = "communication", parameters = listOf(ToolParameter("channel", ToolParameterType.String, true, "Slack channel ID"), ToolParameter("ts", ToolParameterType.String, true, "Message timestamp")), permissions = emptyList()),
        ToolDefinition(name = WHATSAPP_SEND, description = "Send a WhatsApp message to a contact.", category = "communication", parameters = listOf(ToolParameter("recipient", ToolParameterType.String, true, "Phone number or contact name"), ToolParameter("message", ToolParameterType.String, true, "Message content")), permissions = emptyList()),
        ToolDefinition(name = WHATSAPP_SEARCH, description = "Search WhatsApp messages.", category = "communication", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query"), ToolParameter("limit", ToolParameterType.Integer, false, "Limit results")), permissions = emptyList()),
        ToolDefinition(name = X_POST, description = "Post a tweet on X/Twitter.", category = "communication", parameters = listOf(ToolParameter("content", ToolParameterType.String, true, "Tweet content")), permissions = emptyList()),
        ToolDefinition(name = X_REPLY, description = "Reply to a tweet on X/Twitter.", category = "communication", parameters = listOf(ToolParameter("tweetId", ToolParameterType.String, true, "Tweet ID to reply to"), ToolParameter("content", ToolParameterType.String, true, "Reply content")), permissions = emptyList()),
        ToolDefinition(name = X_DM, description = "Send a direct message on X/Twitter.", category = "communication", parameters = listOf(ToolParameter("user", ToolParameterType.String, true, "Username or user ID"), ToolParameter("message", ToolParameterType.String, true, "Message content")), permissions = emptyList()),
        ToolDefinition(name = X_SEARCH, description = "Search tweets on X/Twitter.", category = "communication", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query"), ToolParameter("limit", ToolParameterType.Integer, false, "Limit results")), permissions = emptyList()),
        ToolDefinition(name = VOICE_CALL_START, description = "Start a voice call to a contact.", category = "communication", parameters = listOf(ToolParameter("recipient", ToolParameterType.String, true, "Phone number or contact name")), permissions = emptyList()),
        ToolDefinition(name = VOICE_CALL_STATUS, description = "Get the status of an ongoing voice call.", category = "communication", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = CommunicationPlusToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}