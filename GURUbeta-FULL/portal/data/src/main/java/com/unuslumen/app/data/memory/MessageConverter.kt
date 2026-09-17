package com.unuslumen.app.data.memory

import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.entity.HiveMindStateEntity
import com.unuslumen.app.database.entity.MemoryFactEntity
import com.unuslumen.app.database.entity.MessageEntity
import com.unuslumen.app.database.entity.ConversationThreadEntity
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.memory.MemoryFact

fun Conversation.toEntity() = ConversationEntity(
    id = id,
    title = title,
    createdDate = createdDate,
    updatedDate = updatedDate,
    messageCount = messageCount
)

fun ConversationEntity.toDomain() = Conversation(
    id = id,
    title = title,
    createdDate = createdDate,
    updatedDate = updatedDate,
    messageCount = messageCount
)

fun ConversationMessage.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    embedding = embedding,
    toolCalls = toolCalls,
    toolResults = toolResults
)

fun MessageEntity.toDomain() = ConversationMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    embedding = embedding,
    toolCalls = toolCalls,
    toolResults = toolResults
)

fun MemoryFact.toEntity() = MemoryFactEntity(
    id = id,
    category = category,
    fact = fact,
    embedding = embedding,
    confidence = confidence,
    sourceConversationIds = sourceConversationIds.joinToString(","),
    extractedDate = extractedDate,
    lastRecalledDate = lastRecalledDate,
    layer = layer,
    strength = strength,
    accessCount = accessCount,
    source = source,
    trigger = trigger,
    beforeContext = beforeContext,
    afterContext = afterContext,
    emotionalValence = emotionalValence,
    domain = domain,
    topic = topic,
    subtopic = subtopic,
    signature = signature
)

fun MemoryFactEntity.toDomain() = MemoryFact(
    id = id,
    category = category,
    fact = fact,
    embedding = embedding,
    confidence = confidence,
    sourceConversationIds = sourceConversationIds.split(",").filter { it.isNotBlank() },
    extractedDate = extractedDate,
    lastRecalledDate = lastRecalledDate,
    layer = layer,
    strength = strength,
    accessCount = accessCount,
    source = source,
    trigger = trigger,
    beforeContext = beforeContext,
    afterContext = afterContext,
    emotionalValence = emotionalValence,
    domain = domain,
    topic = topic,
    subtopic = subtopic,
    signature = signature
)

fun ConversationThread.toEntity() = ConversationThreadEntity(
    id = id,
    title = title,
    summary = summary,
    conversationIds = conversationIds.joinToString(","),
    createdDate = createdDate
)

fun ConversationThreadEntity.toDomain() = ConversationThread(
    id = id,
    title = title,
    summary = summary,
    conversationIds = conversationIds.split(",").filter { it.isNotBlank() },
    createdDate = createdDate
)

fun HiveMindStateEntity.toProgress(
    totalFacts: Int,
    totalThreads: Int,
    isProcessing: Boolean
) = HiveMindProgress(
    totalFactsExtracted = totalFactsExtracted,
    totalThreadsDiscovered = totalThreadsDiscovered,
    isProcessing = isProcessing,
    lastProcessingStart = lastProcessingStart,
    lastProcessingEnd = lastProcessingEnd
)

data class HiveMindProgress(
    val totalFactsExtracted: Int,
    val totalThreadsDiscovered: Int,
    val isProcessing: Boolean,
    val lastProcessingStart: Long,
    val lastProcessingEnd: Long
)
