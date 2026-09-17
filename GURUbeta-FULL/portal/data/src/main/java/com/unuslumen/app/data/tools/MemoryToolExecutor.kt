package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.ConversationMessage
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class MemoryToolExecutor(private val memoryRepository: MemoryRepository) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    private fun MemoryFact.toInfo() = MemoryFactInfo(id, category, fact, confidence, extractedDate)
    private fun com.unuslumen.app.domain.memory.Conversation.toInfo() = ConversationInfo(id, title, createdDate, updatedDate, messageCount)
    private fun ConversationMessage.toInfo() = MessageSearchHit(id, conversationId, role, content, timestamp)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        MemoryToolDefinitions.SEARCH_MEMORY_FACTS -> { val q = args["query"] as? String ?: ""; val cat = args["category"] as? String; val facts = if (q.isNotBlank()) memoryRepository.searchFacts(q, cat) else if (cat != null) memoryRepository.getFactsByCategory(cat) else memoryRepository.getAllFacts(); val r = MemoryFactsResult(facts.map { it.toInfo() }); ToolExecutionResult.success(r, json.encodeToString(MemoryFactsResult.serializer(), r)) }
        MemoryToolDefinitions.ADD_MEMORY_FACT -> { val fact = args["fact"] as? String ?: return ToolExecutionResult.error("Missing 'fact'"); val cat = args["category"] as? String ?: return ToolExecutionResult.error("Missing 'category'"); val conf = (args["confidence"] as? Number)?.toFloat() ?: 0.9f; val mf = MemoryFact(id = Uuid.random().toString(), category = cat, fact = fact, confidence = conf.coerceIn(0f, 1f), extractedDate = System.currentTimeMillis(), lastRecalledDate = System.currentTimeMillis()); memoryRepository.persistFacts(listOf(mf)); val r = MemoryFactResult(mf.toInfo()); ToolExecutionResult.success(r, json.encodeToString(MemoryFactResult.serializer(), r)) }
        MemoryToolDefinitions.LIST_MEMORY_FACTS -> { val cat = args["category"] as? String; val facts = if (cat != null) memoryRepository.getFactsByCategory(cat) else memoryRepository.getAllFacts(); val r = MemoryFactsResult(facts.map { it.toInfo() }); ToolExecutionResult.success(r, json.encodeToString(MemoryFactsResult.serializer(), r)) }
        MemoryToolDefinitions.SEARCH_CONVERSATIONS -> { val q = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'"); if (q.trim().length < 3) return ToolExecutionResult.error("Query too short. Use at least 3 characters."); val convs = memoryRepository.searchConversations(q.trim()); val r = SearchConversationsResult(convs.take(20).map { it.toInfo() }); ToolExecutionResult.success(r, json.encodeToString(SearchConversationsResult.serializer(), r)) }
        MemoryToolDefinitions.GET_CONVERSATION_THREAD -> { val tid = args["threadId"] as? String ?: return ToolExecutionResult.error("Missing 'threadId'"); val conv = memoryRepository.getConversation(tid); if (conv == null) return ToolExecutionResult.error("Conversation not found: $tid"); val messages = memoryRepository.getMessagesByConversation(tid); val r = ConversationWithMessagesResult(conv.toInfo(), messages.map { it.toInfo() }); ToolExecutionResult.success(r, json.encodeToString(ConversationWithMessagesResult.serializer(), r)) }
        MemoryToolDefinitions.DELETE_MEMORY_FACT -> { val fid = args["factId"] as? String ?: return ToolExecutionResult.error("Missing 'factId'"); val facts = memoryRepository.getAllFacts(); val fact = facts.find { it.id == fid } ?: return ToolExecutionResult.error("Fact not found: $fid"); memoryRepository.deleteFact(fid); val r = DeleteMemoryFactResult(fid, fact.fact); ToolExecutionResult.success(r, json.encodeToString(DeleteMemoryFactResult.serializer(), r)) }
        MemoryToolDefinitions.UPDATE_MEMORY_FACT -> { val fid = args["factId"] as? String ?: return ToolExecutionResult.error("Missing 'factId'"); val facts = memoryRepository.getAllFacts(); val existing = facts.find { it.id == fid } ?: return ToolExecutionResult.error("Fact not found: $fid"); val now = System.currentTimeMillis(); val updated = existing.copy(fact = (args["fact"] as? String) ?: existing.fact, category = (args["category"] as? String) ?: existing.category, confidence = (args["confidence"] as? Number)?.toFloat() ?: existing.confidence, extractedDate = now, lastRecalledDate = now); memoryRepository.persistFacts(listOf(updated)); val r = MemoryFactResult(updated.toInfo()); ToolExecutionResult.success(r, json.encodeToString(MemoryFactResult.serializer(), r)) }
        MemoryToolDefinitions.SEARCH_ALL_MEMORY -> { val q = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'"); val facts = memoryRepository.searchFacts(q, null); val messages = memoryRepository.searchMessages(q); val toolResults = memoryRepository.searchToolResults(q); val r = UnifiedSearchResult(facts.map { it.toInfo() }, messages.map { it.toInfo() }, toolResults.map { ToolResultHit(it.id, it.toolName, it.resultText, it.timestamp, it.conversationId) }); ToolExecutionResult.success(r, json.encodeToString(UnifiedSearchResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }
}