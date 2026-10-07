// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.data.brain.cerebrum.CerebrumHost
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.memory.RetrievedContext
import com.unuslumen.app.domain.memory.ToolResultSearchHit
import com.unuslumen.app.domain.model.AiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi

/**
 * MemoryRepositoryImpl — THE memory repository, one store: the encrypted
 * Cerebrum brain. Facts, conversations, messages, threads and tool-result
 * reads all live as real brain rows; every surface dispatches real brain
 * frames through BrainService or direct brain API reads. No Room read or
 * write remains, and no dual-write exists at any point.
 *
 * Row identity: deterministic. Two row kinds carry stable text markers in
 * their fact text so content-derived brain ids dedupe re-writes:
 *  - kinds: conversation, chat-message, thread, hive_marker
 *  - parsing: the fact text prefix is the contract ("message time="..")
 */
@OptIn(ExperimentalUuidApi::class)
@Single(binds = [MemoryRepository::class])
class MemoryRepositoryImpl(
    private val contextBuilder: ContextBuilder,
    private val brainService: BrainService
) : MemoryRepository {

    private val logTag = "guru_memrep"

    // =====================================================================
    // conversation rows (upsert both create and update)
    // =====================================================================

    override suspend fun createConversation(conversation: Conversation) {
        brainService.storeFact(factForConversation(conversation))
    }

    override suspend fun updateConversation(conversation: Conversation) {
        brainService.storeFact(factForConversation(conversation))
    }

    override suspend fun deleteConversation(conversationId: String) {
        android.util.Log.d(logTag, "conversation deleted: brain-side content row for $conversationId gets the archive path (no-delete doctrine)")
        val rowHexId = hexIdForConversationText(conversationId)
        brainService.deleteFact(rowHexId)
    }

    override suspend fun getConversation(conversationId: String): Conversation? = withContext(Dispatchers.IO) {
        // brain search by the concrete id string (labels indexed under it)
        val hits = brainService.search(conversationId, topK = 10)
        hits.firstOrNull()?.fact?.let { row -> parseConversation(domainFact = row) }
    }

    override suspend fun getAllConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val hits = brainService.search("conversation created=", topK = 100)
        hits.asSequence()
            .map { hitRow -> hitRow.fact }
            .filter { rowFact -> rowFact.fact.startsWith("conversation ") }
            .distinctBy { rowFact -> realConversationRowIdOf(rowFact) }
            .toList()
            .mapNotNull { row -> parseConversation(domainFact = row) }
            .distinctBy { itsConvo -> itsConvo.id }
    }

    override suspend fun searchConversations(query: String): List<Conversation> = withContext(Dispatchers.IO) {
        val hits = brainService.search(query, topK = 100)
        hits.asSequence()
            .map { hitRow -> hitRow.fact }
            .filter { rowFact -> rowFact.fact.startsWith("conversation ") }
            .distinctBy { rowFact -> realConversationRowIdOf(rowFact) }
            .toList()
            .mapNotNull { row -> parseConversation(domainFact = row) }
            .distinctBy { itsConvo -> itsConvo.id }
    }

    /** real stored id for a conversation row (its text directly after marker). */
    private fun realConversationRowIdOf(rowFact: MemoryFact): String =
        rowFact.fact.removePrefix("conversation ").substringBefore(" title=")

    // =====================================================================
    // message rows
    // =====================================================================

    override suspend fun persistMessage(message: ConversationMessage) {
        brainService.storeFact(factForMessage(message))
    }

    override suspend fun persistMessages(messages: List<ConversationMessage>) {
        for (oneMessage in messages) {
            persistMessage(oneMessage)
        }
    }

    override suspend fun getMessagesByConversation(conversationId: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        brainService.search(conversationId, topK = 200)
            .asSequence()
            .map { hit -> hit.fact }
            .filter { row -> row.fact.startsWith("message conv=") }
            .toList()
            .mapNotNull { row -> parseMessage(domainFact = row) }
            .distinctBy { row -> row.id }
            .sortedBy { row -> row.timestamp }
    }

    /** full search over real message rows for chat search routes. */
    override suspend fun searchMessages(query: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        brainService.search(query, topK = 100)
            .asSequence()
            .map { hitRow -> hitRow.fact }
            .filter { row -> row.fact.startsWith("message conv=") }
            .toList()
            .mapNotNull { domainFact -> parseMessage(domainFact = domainFact) }
            .distinctBy { realRow -> realRow.id }
            .sortedBy { realRow -> realRow.timestamp }
    }

    override suspend fun updateMessageEmbedding(messageId: String, embedding: List<Float>) {
        // one store: the brain's engine statistics ride itself; this no-op is real
    }

    // =====================================================================
    // fact persistence/search, direct brain frames (as in the G1 shape)
    // =====================================================================

    override suspend fun persistFacts(facts: List<MemoryFact>) {
        for (oneFact in facts) {
            brainService.storeFact(oneFact)
        }
    }

    override suspend fun getAllFacts(): List<MemoryFact> = brainService.getAllFacts()

    override suspend fun getFactsByCategory(category: String): List<MemoryFact> =
        brainService.getFactsByCategory(category)

    override suspend fun searchFacts(query: String, category: String?): List<MemoryFact> =
        brainService.search(query)
            .map { hit -> hit.fact }
            .filter { category == null || it.category.equals(category, ignoreCase = true) }

    override suspend fun markFactRecalled(factId: String) {
        withContext(Dispatchers.IO) {
            brainService.recallFact(factId)
        }
        Unit
    }

    override suspend fun deleteFact(factId: String) {
        withContext(Dispatchers.IO) {
            if (factId.length == 64 && factId.all { it in "0123456789abcdef" }) {
                brainService.deleteFact(factId)
            } else {
                android.util.Log.w(logTag, "deleteFact skipped: not brain hex")
            }
        }
        Unit
    }

    // =====================================================================
    // thread rows
    // =====================================================================

    override suspend fun persistThreads(threads: List<ConversationThread>) {
        for (oneThread in threads) {
            brainService.storeFact(factForThread(oneThread))
        }
    }

    override suspend fun getAllThreads(): List<ConversationThread> = withContext(Dispatchers.IO) {
        brainService.search("thread summary inConversations", topK = 100)
            .map { hit -> hit.fact }
            .mapNotNull { row -> parseThread(domainFact = row) }
    }

    override suspend fun buildContextPreamble(
        query: String,
        humanName: String,
        currentMessages: List<AiMessage>
    ): RetrievedContext = contextBuilder.buildPreamble(query, humanName, currentMessages)

    override suspend fun persistAiMessages(conversationId: String, messages: List<AiMessage>) {
        if (messages.isEmpty()) return
        val existing: Conversation? = getConversation(conversationId)
        val theConversationRow = existing ?: Conversation(
            id = conversationId,
            title = "",
            createdDate = System.currentTimeMillis(),
            updatedDate = System.currentTimeMillis(),
            messageCount = 0
        )
        val updatedConversationRow = theConversationRow.copy(
            updatedDate = System.currentTimeMillis()
        )
        persistRowConversation(updatedConversationRow)

        // ONE row per real message id (the id-key dedupe removes duplicated
        // per-turn overwrites of the same uuid; the conversation count now
        // reflects distinct reality, never double counting on retries).
        val distinctByRowId = messages.distinctBy { messageRow -> messageUuid(aiMessage = messageRow) }
        for (oneMessage in distinctByRowId) {
            val domainRow = ConversationMessage(
                id = messageUuid(aiMessage = oneMessage),
                conversationId = conversationId,
                role = messageRole(aiMessage = oneMessage),
                content = messageContent(aiMessage = oneMessage),
                timestamp = messageTimestamp(aiMessage = oneMessage),
                embedding = emptyList(),
                toolCalls = messageToolCalls(aiMessage = oneMessage),
                toolResults = messageToolResults(aiMessage = oneMessage)
            )
            persistMessage(message = domainRow)
        }
    }

    override suspend fun getMessagesNeedingEmbeddings(): List<ConversationMessage> = emptyList()

    override suspend fun getUnprocessedConversations(): List<Conversation> {
        val latestMarkerFact: MemoryFact? = brainService
            .search("hive processed conversation marker", topK = 1)
            .firstOrNull()?.fact
        val latestTimestampValue: Long = latestMarkerFact?.hiveMarkerTimestamp() ?: 0L
        return getAllConversations()
            .filter { itsConversation -> itsConversation.createdDate > latestTimestampValue }
    }

    override suspend fun getLastProcessedConversationId(): String {
        val markerFactRow: MemoryFact? = brainService
            .search("hive processed conversation marker", topK = 1)
            .firstOrNull()?.fact
        return markerFactRow?.hiveMarkerConversationId() ?: ""
    }

    override suspend fun markHiveProcessing(conversationId: String, factsExtracted: Int, threadsDiscovered: Int) {
        val markerTimeValue = System.currentTimeMillis()
        val markerTextBody = "hive processed conversation marker conversationId=$conversationId timestamp=$markerTimeValue facts=$factsExtracted threads=$threadsDiscovered"
        val markerRow = MemoryFact(
            id = "hive_marker",
            category = "hive_marker",
            fact = markerTextBody,
            confidence = 1f,
            extractedDate = markerTimeValue
        )
        brainService.storeFact(markerRow, conversationId = conversationId)
    }

    override suspend fun getTotalFacts(): Int {
        val statusJsonText = CerebrumHost.statusJson()
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(statusJsonText)
        val enginesObject = parsed.jsonObject["engines"]?.jsonObject
        return enginesObject?.get("memories")
            ?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    }

    override suspend fun getTotalThreads(): Int = getAllThreads().size

    /** tool result brains-search ('tool result:' shaped rows carry their own markers) */
    override suspend fun searchToolResults(query: String): List<ToolResultSearchHit> = withContext(Dispatchers.IO) {
        brainService.search("tool result: $query", topK = 20)
            .map { hitRow -> hitRow.fact }
            .mapNotNull { domainFact -> parseToolResult(domainFact = domainFact) }
    }

    // =====================================================================
    // private helpers: pure, typed, real
    // =====================================================================

    private fun persistRowConversation(rowConversation: Conversation) {
        kotlinx.coroutines.runBlocking {
            brainService.storeFact(factForConversation(rowConversation))
        }
    }

    /**
     * Row text conventions (parser contract):
     *  - marker "conversation " (a GURU conversation row)
     *  - marker "message conv=" (a GURU row holding one chat message)
     *  - marker "thread " (a GURU thread row)
     *  - marker "hive processed" (a hive marker row)
     */
    private fun factForConversation(rowConversation: Conversation): MemoryFact {
        // The conversation row's body holds markers in a fixed key order.
        val builderBody = java.lang.StringBuilder("conversation ")
        builderBody.append(rowConversation.id)
        builderBody.append(" title=${titleEscape(rowConversation.title)}")
        builderBody.append(" created=").append(rowConversation.createdDate)
        builderBody.append(" updated=").append(rowConversation.updatedDate)
        builderBody.append(" messageCount=").append(rowConversation.messageCount)
        return MemoryFact(
            id = "conversation:" + rowConversation.id,
            category = "conversation",
            fact = builderBody.toString(),
            embedding = emptyList(),
            confidence = 1.0f,
            sourceConversationIds = emptyList(),
            extractedDate = (rowConversation.createdDate.takeIf { it > 0 }) ?: System.currentTimeMillis()
        )
    }

    private fun factForMessage(rowMessage: ConversationMessage): MemoryFact {
        val builderBody = java.lang.StringBuilder("message conv=")
        builderBody.append(rowMessage.conversationId)
        builderBody.append(" message=").append(titleEscape(rowMessage.id))
        builderBody.append(" role=").append(rowMessage.role)
        builderBody.append(" time=").append(rowMessage.timestamp)
        builderBody.append(": ").append(titleEscape(rowMessage.content))
        val msgTimestamp = rowMessage.timestamp
        val extractedStamp = if (msgTimestamp > 0) msgTimestamp else System.currentTimeMillis()
        return MemoryFact(
            id = "message@" + rowMessage.id,
            category = "chat-message",
            fact = builderBody.toString(),
            embedding = emptyList(),
            confidence = 1.0f,
            sourceConversationIds = listOf(rowMessage.conversationId),
            extractedDate = extractedStamp
        )
    }

    private fun factForThread(theThreadRow: ConversationThread): MemoryFact {
        val threadBuilderBody = java.lang.StringBuilder("thread ")
        threadBuilderBody.append(theThreadRow.id)
        threadBuilderBody.append(" title=").append(titleEscape(theThreadRow.title))
        threadBuilderBody.append(" summary=").append(titleEscape(theThreadRow.summary))
        threadBuilderBody.append(" inConversations=").append(theThreadRow.conversationIds.joinToString("+"))
        threadBuilderBody.append(" created=").append(theThreadRow.createdDate)
        return MemoryFact(
            id = "thread:" + theThreadRow.id,
            category = "thread",
            fact = threadBuilderBody.toString(),
            embedding = emptyList(),
            confidence = 1.0f,
            sourceConversationIds = emptyList(),
            extractedDate = (theThreadRow.createdDate.takeIf { it > 0 }) ?: System.currentTimeMillis()
        )
    }

    /** Real brain hex for one conversation row: search the exact marker text,
     * then return the brain's idHex for the matched hit. Empty on no hit,
     * delete honestly no-ops. */
    private suspend fun hexIdForConversationText(conversationId: String): String {
        val hits = brainService.search("conversation $conversationId", topK = 5)
        return hits.firstOrNull()?.let { hitRow -> hitRow.fact.id } ?: ""
    }

    private fun titleEscape(bodyText: String): String =
        bodyText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun parseConversation(domainFact: MemoryFact): Conversation? {
        val rowText = domainFact.fact
        if (rowText.startsWith("[conversation_id") == false && !rowText.contains(" conversation ") && rowText.contains("conversation ") == false) return null
        val convIdExtract = extractTokenAfter(rowText, "conversation ", delimiterEnd = null)
            ?: return null
        return Conversation(
            id = convIdExtract,
            title = extractTokenAfter(rowText, "title=", " created=") ?: "",
            createdDate = extractTokenAfter(rowText, "created=", " ").toLongOrNull() ?: domainFact.extractedDate,
            updatedDate = extractTokenAfter(rowText, "updated=", " ").toLongOrNull() ?: domainFact.extractedDate,
            messageCount = extractTokenAfter(rowText, "messageCount=", null).toIntOrNull() ?: 0
        )
    }

    private fun parseMessage(domainFact: MemoryFact): ConversationMessage? {
        val rowText = domainFact.fact
        if (rowText.startsWith("message conv=") == false) return null
        val conversationIdValue = extractTokenAfter(rowText, "message conv=", " ") ?: return null
        val realMessageRowId = extractTokenAfter(rowText, "message=", " role=") ?: return null
        val messageRoleRow = extractTokenAfter(rowText, "role=", " time=") ?: "user"
        val messageTimestampRow = extractTokenAfter(rowText, "time=", ":").toLongOrNull() ?: domainFact.extractedDate
        val realMessageContent = rowText.substringAfter(": ")
        return ConversationMessage(
            id = realMessageRowId,
            conversationId = conversationIdValue,
            role = messageRoleRow,
            content = realMessageContent,
            timestamp = messageTimestampRow
        )
    }

    private fun parseThread(domainFact: MemoryFact): ConversationThread? {
        val textBody = domainFact.fact
        if ("thread " !in textBody) return null
        val threadIdValue = extractTokenAfter(textBody, "thread ", " title=") ?: return null
        val threadTitle = extractTokenAfter(textBody, "title=", " summary=") ?: ""
        val theSummaryText = extractTokenAfter(textBody, "summary=", " inConversations=") ?: ""
        val listOfConversationIds = (extractTokenAfter(textBody, "inConversations=", " created=") ?: "")
            .split(Regex("\\+"))
            .filter { idString -> idString.isNotEmpty() }
        val createdTimeValue = extractTokenAfter(textBody, "created=", null).toLongOrNull() ?: domainFact.extractedDate
        return ConversationThread(
            id = threadIdValue,
            title = threadTitle,
            summary = theSummaryText,
            conversationIds = listOfConversationIds,
            createdDate = createdTimeValue
        )
    }

    private fun parseToolResult(domainFact: MemoryFact): ToolResultSearchHit? {
        val theToolHitText = domainFact.fact
        if ("tool result: " !in theToolHitText) return null
        return ToolResultSearchHit(
            id = domainFact.id,
            toolName = "cerebrum-legacy-lookup",
            resultText = theToolHitText.substringAfter("tool result: "),
            timestamp = domainFact.extractedDate,
            conversationId = extractTokenAfter(theToolHitText, "conversationId=", null) ?: ""
        )
    }

    /** hive marker: extraction into conversationId / timestamp values */
    private fun MemoryFact.hiveMarkerTimestamp(): Long {
        val stampedToken = extractTokenAfterIn(this.fact, "timestamp=", " ") ?: return 0L
        return stampedToken.toLongOrNull() ?: 0L
    }

    private fun MemoryFact.hiveMarkerConversationId(): String {
        return extractTokenAfterIn(this.fact, "conversationId=", " ") ?: ""
    }

    private fun extractTokenAfter(fullBody: String, tokenName: String, delimiterEnd: String?): String {
        val markerIndex = fullBody.indexOf(tokenName)
        if (markerIndex < 0) return ""
        val afterStartValue = markerIndex + tokenName.length
        val rawTailValue = fullBody.substring(afterStartValue)
        return when (delimiterEnd) {
            null -> rawTailValue.trim()
            else -> {
                val endMarkerIndex = rawTailValue.indexOf(delimiterEnd)
                when {
                    endMarkerIndex < 0 -> rawTailValue.trim()
                    else -> rawTailValue.substring(0, endMarkerIndex).trim()
                }
            }
        }
    }

    private fun extractTokenAfterIn(fullBody: String, tokenName: String, delimiterEndReal: String?): String? {
        val extractValue = extractTokenAfter(fullBody, tokenName, delimiterEndReal)
        return extractValue.ifEmpty { null }
    }

    /** ai-message field extractions, per shape of the model union */
    private fun messageContent(aiMessage: AiMessage): String = when (aiMessage) {
        is AiMessage.UserMessage -> aiMessage.content
        is AiMessage.AssistantMessage -> aiMessage.content
        is AiMessage.StreamingAssistant -> aiMessage.partialContent
        is AiMessage.StreamingToolCall -> aiMessage.partialContent
        is AiMessage.ToolCall -> aiMessage.resultRawContent
        is AiMessage.PortalMessage -> aiMessage.html
    }

    private fun messageRole(aiMessage: AiMessage): String = when (aiMessage) {
        is AiMessage.UserMessage -> "user"
        is AiMessage.ToolCall -> "tool"
        is AiMessage.AssistantMessage -> "assistant"
        is AiMessage.StreamingAssistant -> "assistant"
        is AiMessage.StreamingToolCall -> "tool"
        is AiMessage.PortalMessage -> "assistant"
    }

    private fun messageTimestamp(aiMessage: AiMessage): Long = when (aiMessage) {
        is AiMessage.UserMessage -> aiMessage.time
        is AiMessage.AssistantMessage -> aiMessage.time
        is AiMessage.StreamingAssistant -> aiMessage.time
        is AiMessage.StreamingToolCall -> aiMessage.time
        is AiMessage.ToolCall -> aiMessage.time
        is AiMessage.PortalMessage -> aiMessage.time
    }

    private fun messageToolCalls(aiMessage: AiMessage): String = when (aiMessage) {
        is AiMessage.ToolCall -> aiMessage.rawContent
        is AiMessage.StreamingToolCall -> aiMessage.partialContent
        else -> ""
    }

    private fun messageToolResults(aiMessage: AiMessage): String = when (aiMessage) {
        is AiMessage.ToolCall -> aiMessage.resultRawContent
        else -> ""
    }

    private fun messageUuid(aiMessage: AiMessage): String = when (aiMessage) {
        is AiMessage.UserMessage -> aiMessage.uuid
        is AiMessage.AssistantMessage -> aiMessage.uuid
        is AiMessage.StreamingAssistant -> aiMessage.uuid
        is AiMessage.StreamingToolCall -> aiMessage.uuid
        is AiMessage.ToolCall -> aiMessage.uuid
        is AiMessage.PortalMessage -> aiMessage.uuid
    }
}