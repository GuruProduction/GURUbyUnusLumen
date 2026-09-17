package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class MemoryFactInfo(val id: String, val category: String, val fact: String, val confidence: Float, val extractedDate: Long) : ToolResultData
@Serializable data class MemoryFactsResult(val facts: List<MemoryFactInfo>) : ToolResultData
@Serializable data class MemoryFactResult(val fact: MemoryFactInfo) : ToolResultData
@Serializable data class DeleteMemoryFactResult(val deletedFactId: String, val deletedFact: String) : ToolResultData
@Serializable data class ConversationInfo(val id: String, val title: String, val createdDate: Long, val updatedDate: Long, val messageCount: Int) : ToolResultData
@Serializable data class SearchConversationsResult(val conversations: List<ConversationInfo>) : ToolResultData
@Serializable data class ConversationThreadInfo(val id: String, val title: String, val conversationIds: List<String>) : ToolResultData
@Serializable data class ConversationThreadResult(val thread: ConversationThreadInfo) : ToolResultData
@Serializable data class MessageSearchHit(val id: String, val conversationId: String, val role: String, val content: String, val timestamp: Long) : ToolResultData
@Serializable data class ToolResultHit(val id: String, val toolName: String, val resultText: String, val timestamp: Long, val conversationId: String) : ToolResultData
@Serializable data class UnifiedSearchResult(val facts: List<MemoryFactInfo>, val messages: List<MessageSearchHit>, val toolResults: List<ToolResultHit>) : ToolResultData
@Serializable data class ConversationWithMessagesResult(val conversation: ConversationInfo, val messages: List<MessageSearchHit>) : ToolResultData