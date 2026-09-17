package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class DiscordResult(val success: Boolean, val action: String? = null, val messageId: String? = null, val error: String? = null) : ToolResultData
@Serializable data class DiscordReadResult(val success: Boolean, val messages: List<DiscordMessage>, val error: String? = null) : ToolResultData
@Serializable data class DiscordMessage(val id: String, val author: String, val content: String, val timestamp: String) : ToolResultData
@Serializable data class SlackResult(val success: Boolean, val action: String? = null, val messageId: String? = null, val error: String? = null) : ToolResultData
@Serializable data class SlackReadResult(val success: Boolean, val messages: List<SlackMessage>, val error: String? = null) : ToolResultData
@Serializable data class SlackMessage(val ts: String, val user: String, val text: String) : ToolResultData
@Serializable data class WhatsAppResult(val success: Boolean, val action: String? = null, val recipient: String? = null, val messageId: String? = null, val error: String? = null) : ToolResultData
@Serializable data class WhatsAppSearchResult(val success: Boolean, val messages: List<WhatsAppMessage>, val error: String? = null) : ToolResultData
@Serializable data class WhatsAppMessage(val id: String, val sender: String, val content: String, val timestamp: String) : ToolResultData
@Serializable data class XResult(val success: Boolean, val action: String? = null, val tweetId: String? = null, val error: String? = null) : ToolResultData
@Serializable data class XSearchResult(val success: Boolean, val tweets: List<Tweet>, val error: String? = null) : ToolResultData
@Serializable data class Tweet(val id: String, val author: String, val content: String, val timestamp: String) : ToolResultData
@Serializable data class VoiceCallResult(val success: Boolean, val action: String? = null, val status: String, val callId: String? = null, val error: String? = null) : ToolResultData