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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi

/**
 * MemoryRepositoryImpl — THE memory repository, hybrid post-boundary-reset
 * (the architecture fix Steven ruled on):
 *  - CHAT DRAWER (Rooms conversations + messages + tool_results): raw
 *    transcripts, tool result texts render/scale exactly with Room shapes,
 *    they rehydrated on first boot from the one-store migration rows inside
 *    Cerebrum; ongoing writes land Room-side only.
 *  - MEMORY BRAIN (Cerebrum via BrainService/CerebrumBrainApi): the hive's
 *    fact-extraction + thread-discovery memory facts (real brain lane), the
 *    memory tool family's real facts, and its marker rows. Never raw chat.
 *
 * The brain keeps the parser contract for its two REAL marker lanes
 * (thread / hive_marker); any conversation/message-shaped brain rows are
 * historical rehydration sources for the drawer after first boot.
 */
@OptIn(ExperimentalUuidApi::class)
@Single(binds = [MemoryRepository::class])
class MemoryRepositoryImpl(
    private val contextBuilder: ContextBuilder,
    private val brainService: BrainService,
    private val conversationDao: com.unuslumen.app.database.dao.ConversationDao,
    private val messageDao: com.unuslumen.app.database.dao.MessageDao
) : MemoryRepository {

    private val logTag = "guru_memrep"

    // =====================================================================
    // conversation rows — ONE STORE = Room again (chat drawer).
    // Cerebrum stays the MEMORY brain: extraction writes facts/threads via
    // the hive, never raw chat. Chat rows stopped landing in the vault.
    // =====================================================================

    override suspend fun createConversation(conversation: Conversation) {
        conversationDao.upsertConversation(entityForConversation(conversation))
        // Legacy brain marker row retirement marker: raw chat content stopped
        // being written here since the one-store boundary reset. No brain write.
    }

    override suspend fun updateConversation(conversation: Conversation) {
        conversationDao.upsertConversation(entityForConversation(conversation))
    }

    override suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteConversationById(conversationId)
        messageDao.deleteMessagesByConversation(conversationId)
    }

    override suspend fun getConversation(conversationId: String): Conversation? = withContext(Dispatchers.IO) {
        conversationDao.getConversation(conversationId)?.let { domainOfEntity(it) }
    }

    override suspend fun getAllConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        conversationDao.getAllConversations().first().map { entity -> domainOfEntity(entity) }
    }

    override suspend fun searchConversations(query: String): List<Conversation> = withContext(Dispatchers.IO) {
        val titleHits = conversationDao.searchConversations(query).first()
            .map { entity -> domainOfEntity(entity) }
        val messageHits = conversationDao.searchConversationsWithMessages(query).first()
            .map { entity -> domainOfEntity(entity) }
        (messageHits + titleHits).distinctBy { it.id }
    }

    // =====================================================================
    // message rows — Room store for chat history (same drawer rehydration).
    // =====================================================================

    override suspend fun persistMessage(message: ConversationMessage) {
        messageDao.insertMessage(entityForMessage(message))
    }

    override suspend fun persistMessages(messages: List<ConversationMessage>) {
        for (oneMessage in messages) {
            persistMessage(oneMessage)
        }
    }

    override suspend fun getMessagesByConversation(conversationId: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        messageDao.getMessagesByConversation(conversationId)
            .map { entity -> domainOfEntity(entity) }
    }

    /** full search over real message rows for chat search routes (Room FTS). */
    override suspend fun searchMessages(query: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        val hits = try {
            messageDao.searchMessagesFts(query)
        } catch (e: Exception) {
            messageDao.searchMessagesLike(query)
        }
        hits.map { entity -> domainOfEntity(entity) }
            .distinctBy { row -> row.id }
            .sortedBy { row -> row.timestamp }
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

    /** tool result brains-search ('tool result:' shaped rows carry their own markers).
     * ONE STORE: tool RESULTS live in Room (their real store); search rides the
     * FTS index the DAO exposes. The brain stopped holding raw tool-result
     * dumps with the one-store boundary reset. */
    override suspend fun searchToolResults(query: String): List<ToolResultSearchHit> =
        builtInToolResultSearch(toolResultDaoLocal(), query)

    // =====================================================================
    // entity converters (Room <-> domain). ONE chat drawer, ONE shape each way.
    // =====================================================================

    private fun entityForConversation(rowConversation: Conversation): com.unuslumen.app.database.entity.ConversationEntity {
        return com.unuslumen.app.database.entity.ConversationEntity(
            id = rowConversation.id,
            title = rowConversation.title,
            createdDate = rowConversation.createdDate,
            updatedDate = rowConversation.updatedDate,
            messageCount = rowConversation.messageCount
        )
    }

    private fun domainOfEntity(entity: com.unuslumen.app.database.entity.ConversationEntity): Conversation {
        return Conversation(
            id = entity.id,
            title = entity.title,
            createdDate = entity.createdDate,
            updatedDate = entity.updatedDate,
            messageCount = entity.messageCount
        )
    }

    private fun entityForMessage(rowMessage: ConversationMessage): com.unuslumen.app.database.entity.MessageEntity {
        return com.unuslumen.app.database.entity.MessageEntity(
            id = rowMessage.id,
            conversationId = rowMessage.conversationId,
            role = rowMessage.role,
            content = rowMessage.content,
            timestamp = rowMessage.timestamp,
            embedding = emptyList(),
            toolCalls = rowMessage.toolCalls,
            toolResults = rowMessage.toolResults
        )
    }

    private fun domainOfEntity(entity: com.unuslumen.app.database.entity.MessageEntity): ConversationMessage {
        return ConversationMessage(
            id = entity.id,
            conversationId = entity.conversationId,
            role = entity.role,
            content = entity.content,
            timestamp = entity.timestamp,
            embedding = emptyList(),
            toolCalls = entity.toolCalls,
            toolResults = entity.toolResults
        )
    }

    private fun toolResultDaoLocal(): com.unuslumen.app.database.dao.ToolResultDao {
        return org.koin.java.KoinJavaComponent.getKoin()
            .get<com.unuslumen.app.database.dao.ToolResultDao>()
    }

    /** Ongoing conversation-meta writes land through here (upsert semantics,
     * no runBlocking anywhere on the Room drawer path). */
    private suspend fun persistRowConversation(rowConversation: Conversation) {
        conversationDao.upsertConversation(entityForConversation(rowConversation))
    }

    /**
     * Rehydrate (one pass, idempotent, boot-time): pull every conversation +
     * message row the one-store migration pushed into Cerebrum back into Room.
     *SharedPreferences-flag guarded so it runs exactly once and never races
     * the live writer afterwards. Room becomes the drawer's truth on arrival.
     */
    suspend fun rehydrateChatDrawerFromBrain() = withContext(Dispatchers.IO) {
        val prefs = contextForHelper().getSharedPreferences(HYDRATION_PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DRAWER_REHYDRATED, false)) {
            android.util.Log.d(logTag, "chat drawer rehydrate: already complete, skip")
            return@withContext
        }

        val convRows = brainService.search("conversation created=", topK = 400)
        var convImported = 0
        for (hit in convRows) {
            val domainRow = parseConversation(domainFact = hit.fact) ?: continue
            if (domainRow.id.isBlank()) continue
            conversationDao.upsertConversation(entityForConversation(domainRow))
            convImported++
        }

        val msgRows = brainService.search("message conv=", topK = 1600)
        var msgImported = 0
        for (hit in msgRows) {
            val domainRow = parseMessage(domainFact = hit.fact) ?: continue
            if (domainRow.id.isBlank() || domainRow.conversationId.isBlank()) continue
            messageDao.insertMessage(entityForMessage(domainRow))
            msgImported++
        }

        // Honest messageCount per rehydrated conversation row.
        for (oneConversation in conversationDao.getAllConversations().first()) {
            val actualCount = messageDao.getMessagesByConversation(oneConversation.id).size
            if (actualCount != oneConversation.messageCount) {
                conversationDao.updateConversationMeta(
                    oneConversation.id,
                    oneConversation.title,
                    oneConversation.updatedDate,
                    actualCount
                )
            }
        }
        prefs.edit().putBoolean(KEY_DRAWER_REHYDRATED, true).apply()
        android.util.Log.i(
            logTag,
            "chat drawer rehydrate: conversations=$convImported messages=$msgImported (brain markers rehydrated into Room)"
        )
        Unit
    }

    private fun contextForHelper(): android.content.Context =
        org.koin.java.KoinJavaComponent.getKoin()
            .get<android.content.Context>()

    private companion object {
        const val HYDRATION_PREFS = "guru_mem_rep_hydration"
        const val KEY_DRAWER_REHYDRATED = "drawer_rehydrated_v1"
    }

    private suspend fun builtInToolResultSearch(
        toolResultDao: com.unuslumen.app.database.dao.ToolResultDao,
        query: String,
    ): List<ToolResultSearchHit> = withContext(Dispatchers.IO) {
        val rows = try {
            toolResultDao.searchToolResultsFts(query, 20)
        } catch (e: Exception) {
            toolResultDao.searchToolResultsLike(query)
        }
        rows.map { entityRow ->
            ToolResultSearchHit(
                id = entityRow.id,
                toolName = entityRow.toolName,
                resultText = entityRow.resultText,
                timestamp = entityRow.timestamp,
                conversationId = entityRow.conversationId
            )
        }
    }

    // =====================================================================
    // marker-to-domain fact writers (thread + hive marker only; chat lanes
    // write Room now and their old marker writers retired with the drawer)
    // =====================================================================

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

    /** Chat-lane reverse rehydrators, typed; read the brain rows carrying
     * the same fixed markers the one-store writes had, convert to Room
     * entities, and rehydrate the drawer after the migration. */
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