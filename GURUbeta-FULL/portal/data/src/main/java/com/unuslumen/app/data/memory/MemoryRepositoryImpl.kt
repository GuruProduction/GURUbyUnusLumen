package com.unuslumen.app.data.memory

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.database.dao.ConversationDao
import com.unuslumen.app.database.dao.ConversationThreadDao
import com.unuslumen.app.database.dao.HiveMindStateDao
import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.dao.MessageDao
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.database.entity.HiveMindStateEntity
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.memory.RetrievedContext
import com.unuslumen.app.domain.memory.ToolResultSearchHit
import com.unuslumen.app.domain.model.AiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [MemoryRepository::class])
class MemoryRepositoryImpl(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val memoryFactDao: MemoryFactDao,
    private val conversationThreadDao: ConversationThreadDao,
    private val hiveMindStateDao: HiveMindStateDao,
    private val contextBuilder: ContextBuilder,
    private val brainService: BrainService,
    private val toolResultDao: ToolResultDao
) : MemoryRepository {

    override suspend fun createConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        conversationDao.upsertConversation(conversation.toEntity())
    }

    override suspend fun updateConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        conversationDao.updateConversation(conversation.toEntity())
    }

    override suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        conversationDao.deleteConversationById(conversationId)
    }

    override suspend fun getConversation(conversationId: String): Conversation? = withContext(Dispatchers.IO) {
        conversationDao.getConversation(conversationId)?.toDomain()
    }

    override suspend fun getAllConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        conversationDao.getAllConversations().first().map { it.toDomain() }
    }

    override suspend fun searchConversations(query: String): List<Conversation> = withContext(Dispatchers.IO) {
        conversationDao.searchConversationsWithMessages(query).first().map { it.toDomain() }
    }

    override suspend fun persistMessage(message: ConversationMessage) = withContext(Dispatchers.IO) {
        messageDao.insertMessage(message.toEntity())
    }

    override suspend fun persistMessages(messages: List<ConversationMessage>) = withContext(Dispatchers.IO) {
        if (messages.isNotEmpty()) {
            messageDao.insertMessages(messages.map { it.toEntity() })
        }
    }

    override suspend fun getMessagesByConversation(conversationId: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        messageDao.getMessagesByConversation(conversationId).map { it.toDomain() }
    }

    override suspend fun updateMessageEmbedding(messageId: String, embedding: List<Float>) = withContext(Dispatchers.IO) {
        val embeddingString = embedding.joinToString(",")
        messageDao.updateEmbedding(messageId, embeddingString)
    }

    /**
     * Persist facts through BrainService so each fact gets:
     * - 256-bit binary signature for fast similarity search
     * - Domain/topic/subtopic classification
     * - Episodic context (source, trigger, emotional valence)
     * - Cross-references to related existing facts
     * - Semantic edges in the graph
     * - Event recorded in the audit trail
     * - Embedding generated asynchronously in the background
     */
    override suspend fun persistFacts(facts: List<MemoryFact>) = withContext(Dispatchers.IO) {
        for (fact in facts) {
            val entity = fact.toEntity()
            brainService.storeFact(entity)
        }
    }

    override suspend fun getAllFacts(): List<MemoryFact> = withContext(Dispatchers.IO) {
        memoryFactDao.getAllFacts().map { it.toDomain() }
    }

    override suspend fun getFactsByCategory(category: String): List<MemoryFact> = withContext(Dispatchers.IO) {
        memoryFactDao.getFactsByCategory(category).map { it.toDomain() }
    }

    /**
     * Search facts using FTS5 full-text search. Falls back to LIKE if FTS5
     * is not available. Returns every matching fact ordered by most recent.
     * Optional category filter applied after search.
     */
    override suspend fun searchFacts(query: String, category: String?): List<MemoryFact> = withContext(Dispatchers.IO) {
        val results = try {
            memoryFactDao.searchFactsFts5(query)
        } catch (e: Exception) {
            memoryFactDao.searchFactsByFactText(query)
        }
        val facts = results.map { it.toDomain() }
        if (category != null) {
            facts.filter { it.category.equals(category, ignoreCase = true) }
        } else {
            facts
        }
    }

    /**
     * Recall a fact through BrainService so it gets:
     * - Strength boost (recall reinforces the memory)
     * - Access count increment
     * - Layer promotion check (BUFFER -> EPISODIC -> SEMANTIC)
     * - Added to the hot cache for instant future retrieval
     */
    override suspend fun markFactRecalled(factId: String) = withContext(Dispatchers.IO) {
        brainService.recallFact(factId)
    }

    override suspend fun deleteFact(factId: String) = withContext(Dispatchers.IO) {
        brainService.deleteFact(factId)
        Unit
    }

    override suspend fun persistThreads(threads: List<ConversationThread>) = withContext(Dispatchers.IO) {
        if (threads.isNotEmpty()) {
            conversationThreadDao.insertThreads(threads.map { it.toEntity() })
        }
    }

    override suspend fun getAllThreads(): List<ConversationThread> = withContext(Dispatchers.IO) {
        conversationThreadDao.getAllThreads().map { it.toDomain() }
    }

    /**
     * Build the context preamble through ContextBuilder which now uses
     * BrainService's four-tier retrieval (FTS5, signature, embedding, graph)
     * for fact search instead of the old VectorSearchEngine.
     */
    override suspend fun buildContextPreamble(query: String, humanName: String, currentMessages: List<AiMessage>): RetrievedContext = contextBuilder.buildPreamble(query, humanName, currentMessages)

    override suspend fun persistAiMessages(conversationId: String, messages: List<AiMessage>) = withContext(Dispatchers.IO) {
        val existingConversation = conversationDao.getConversation(conversationId)
        if (existingConversation == null) {
            conversationDao.upsertConversation(
                com.unuslumen.app.database.entity.ConversationEntity(
                    id = conversationId,
                    title = "",
                    createdDate = System.currentTimeMillis(),
                    updatedDate = System.currentTimeMillis(),
                    messageCount = 0
                )
            )
        }

        val domainMessages = messages.mapIndexed { index, aiMessage ->
            ConversationMessage(
                id = when (aiMessage) {
                    is AiMessage.UserMessage -> aiMessage.uuid
                    is AiMessage.AssistantMessage -> aiMessage.uuid
                    is AiMessage.StreamingAssistant -> aiMessage.uuid
                    is AiMessage.StreamingToolCall -> aiMessage.uuid
                    is AiMessage.ToolCall -> aiMessage.uuid
                    is AiMessage.PortalMessage -> aiMessage.uuid
                },
                conversationId = conversationId,
                role = when (aiMessage) {
                    is AiMessage.UserMessage -> "user"
                    is AiMessage.ToolCall -> "tool"
                    is AiMessage.AssistantMessage -> "assistant"
                    is AiMessage.StreamingAssistant -> "assistant"
                    is AiMessage.StreamingToolCall -> "tool"
                    is AiMessage.PortalMessage -> "assistant"
                },
                content = when (aiMessage) {
                    is AiMessage.UserMessage -> aiMessage.content
                    is AiMessage.AssistantMessage -> aiMessage.content
                    is AiMessage.StreamingAssistant -> aiMessage.partialContent
                    is AiMessage.StreamingToolCall -> aiMessage.partialContent
                    is AiMessage.ToolCall -> aiMessage.resultRawContent
                    is AiMessage.PortalMessage -> aiMessage.html
                },
                timestamp = when (aiMessage) {
                    is AiMessage.UserMessage -> aiMessage.time
                    is AiMessage.AssistantMessage -> aiMessage.time
                    is AiMessage.StreamingAssistant -> aiMessage.time
                    is AiMessage.StreamingToolCall -> aiMessage.time
                    is AiMessage.ToolCall -> aiMessage.time
                    is AiMessage.PortalMessage -> aiMessage.time
                },
                toolCalls = when (aiMessage) {
                    is AiMessage.ToolCall -> aiMessage.rawContent
                    is AiMessage.StreamingToolCall -> aiMessage.partialContent
                    else -> ""
                },
                toolResults = when (aiMessage) {
                    is AiMessage.ToolCall -> aiMessage.resultRawContent
                    else -> ""
                }
            )
        }

        messageDao.replaceMessagesForConversation(conversationId, domainMessages.map { it.toEntity() })
    }

    override suspend fun getMessagesNeedingEmbeddings(): List<ConversationMessage> = withContext(Dispatchers.IO) {
        messageDao.getMessagesWithoutEmbeddings().map { it.toDomain() }
    }

    override suspend fun getUnprocessedConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val lastProcessedTimestamp = hiveMindStateDao.getState()?.lastProcessedTimestamp ?: 0L
        val all = conversationDao.getAllConversations().first().map { it.toDomain() }
        all.filter { it.createdDate > lastProcessedTimestamp }
    }

    override suspend fun getLastProcessedConversationId(): String = withContext(Dispatchers.IO) {
        hiveMindStateDao.getState()?.lastProcessedConversationId ?: ""
    }

    override suspend fun markHiveProcessing(conversationId: String, factsExtracted: Int, threadsDiscovered: Int) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val existing = hiveMindStateDao.getState()
        if (existing == null) {
            hiveMindStateDao.insertState(
                HiveMindStateEntity(
                    id = 1,
                    lastProcessedConversationId = conversationId,
                    lastProcessedTimestamp = timestamp,
                    totalFactsExtracted = factsExtracted,
                    totalThreadsDiscovered = threadsDiscovered,
                    isProcessing = false,
                    lastProcessingStart = timestamp,
                    lastProcessingEnd = timestamp
                )
            )
        } else {
            hiveMindStateDao.updateProcessedState(
                conversationId = conversationId,
                timestamp = timestamp,
                factsAdded = factsExtracted,
                processingStart = existing.lastProcessingStart,
                processingEnd = timestamp,
                isProcessing = false
            )
            if (threadsDiscovered > 0) {
                hiveMindStateDao.updateThreadCount(threadsDiscovered)
            }
        }
    }

    override suspend fun getTotalFacts(): Int = withContext(Dispatchers.IO) {
        memoryFactDao.getFactCount()
    }

    override suspend fun getTotalThreads(): Int = withContext(Dispatchers.IO) {
        conversationThreadDao.getThreadCount()
    }

    /**
     * Search all messages by text content. Tries FTS5 first, falls back to LIKE
     * if FTS5 is not available on the device. Returns every matching message
     * ordered by most recent first.
     */
    override suspend fun searchMessages(query: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        val results = try {
            messageDao.searchMessagesFts(query)
        } catch (e: Exception) {
            messageDao.searchMessagesLike(query)
        }
        results.map { it.toDomain() }
    }

    /**
     * Search all tool results using FTS5 full-text search. Falls back to LIKE.
     * Returns every matching tool result ordered by most recent first.
     */
    override suspend fun searchToolResults(query: String): List<ToolResultSearchHit> = withContext(Dispatchers.IO) {
        val results = try {
            toolResultDao.searchToolResultsFts(query, 10000)
        } catch (e: Exception) {
            toolResultDao.searchToolResultsLike(query)
        }
        results.map {
            ToolResultSearchHit(
                id = it.id,
                toolName = it.toolName,
                resultText = it.resultText,
                timestamp = it.timestamp,
                conversationId = it.conversationId
            )
        }
    }
}
