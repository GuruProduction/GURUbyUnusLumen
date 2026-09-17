package com.unuslumen.app.domain.memory

import com.unuslumen.app.domain.model.AiMessage

interface MemoryRepository {
    suspend fun createConversation(conversation: Conversation)
    suspend fun updateConversation(conversation: Conversation)
    suspend fun deleteConversation(conversationId: String)
    suspend fun getConversation(conversationId: String): Conversation?
    suspend fun getAllConversations(): List<Conversation>
    suspend fun searchConversations(query: String): List<Conversation>

    suspend fun persistMessage(message: ConversationMessage)
    suspend fun persistMessages(messages: List<ConversationMessage>)
    suspend fun getMessagesByConversation(conversationId: String): List<ConversationMessage>
    suspend fun updateMessageEmbedding(messageId: String, embedding: List<Float>)

    suspend fun persistFacts(facts: List<MemoryFact>)
    suspend fun getAllFacts(): List<MemoryFact>
    suspend fun getFactsByCategory(category: String): List<MemoryFact>
    suspend fun searchFacts(query: String, category: String? = null): List<MemoryFact>
    suspend fun markFactRecalled(factId: String)
    suspend fun deleteFact(factId: String)

    suspend fun persistThreads(threads: List<ConversationThread>)
    suspend fun getAllThreads(): List<ConversationThread>

    suspend fun buildContextPreamble(query: String, humanName: String = "", currentMessages: List<AiMessage> = emptyList()): RetrievedContext
    suspend fun persistAiMessages(conversationId: String, messages: List<AiMessage>)

    suspend fun getMessagesNeedingEmbeddings(): List<ConversationMessage>
    suspend fun getUnprocessedConversations(): List<Conversation>

    suspend fun getLastProcessedConversationId(): String
    suspend fun markHiveProcessing(conversationId: String, factsExtracted: Int, threadsDiscovered: Int)

    suspend fun getTotalFacts(): Int
    suspend fun getTotalThreads(): Int

    suspend fun searchMessages(query: String): List<ConversationMessage>
    suspend fun searchToolResults(query: String): List<ToolResultSearchHit>
}
