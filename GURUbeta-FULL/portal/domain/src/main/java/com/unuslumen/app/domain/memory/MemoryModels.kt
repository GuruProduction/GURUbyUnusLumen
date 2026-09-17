package com.unuslumen.app.domain.memory

data class Conversation(
    val id: String,
    val title: String,
    val createdDate: Long,
    val updatedDate: Long,
    val messageCount: Int = 0
)

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val embedding: List<Float> = emptyList(),
    val toolCalls: String = "",
    val toolResults: String = ""
)

data class MemoryFact(
    val id: String,
    val category: String,
    val fact: String,
    val embedding: List<Float> = emptyList(),
    val confidence: Float = 1.0f,
    val sourceConversationIds: List<String> = emptyList(),
    val extractedDate: Long,
    val lastRecalledDate: Long = 0L,
    // Brain fields
    val layer: String = "BUFFER",
    val strength: Float = 1.0f,
    val accessCount: Int = 0,
    val source: String = "",
    val trigger: String = "",
    val beforeContext: String = "",
    val afterContext: String = "",
    val emotionalValence: Float = 0.0f,
    val domain: String = "",
    val topic: String = "",
    val subtopic: String = "",
    val signature: String = ""
)

data class ConversationThread(
    val id: String,
    val title: String,
    val summary: String,
    val conversationIds: List<String> = emptyList(),
    val createdDate: Long
)

data class RetrievedContext(
    val messages: List<ConversationMessage>,
    val facts: List<MemoryFact>,
    val threads: List<ConversationThread>,
    val preamble: String
)

data class ToolResultSearchHit(
    val id: String,
    val toolName: String,
    val resultText: String,
    val timestamp: Long,
    val conversationId: String
)
