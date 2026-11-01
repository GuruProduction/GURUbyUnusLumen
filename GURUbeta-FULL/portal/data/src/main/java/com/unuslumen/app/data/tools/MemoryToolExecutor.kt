// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.memory.persistedFactRoundTrip
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.ConversationMessage
import kotlinx.serialization.json.Json

/**
 * MemoryToolExecutor — the numen's memory tools.
 *
 * ONE STORE: the encrypted Cerebrum brain for facts (search, add, update,
 * delete), ONE drawer (Room) for chat threads + tool results.
 *
 * THE IDENTITY RULE, which is what issue 201 was about: the id this tool family
 * reports is ALWAYS the brain's own deterministic 64-hex id, and every id it is
 * given is resolved by an exact lookup. Both halves matter. An invented id can
 * never be resolved by the brain, and proving existence with a relevance search
 * reports "not found" for a fact the brain holds whenever that fact does not
 * happen to share a word with the search string.
 *
 * So: no uuid is ever created here, no listing is used as an existence check,
 * and a failure is reported as a failure with the reason attached.
 */
class MemoryToolExecutor(private val memoryRepository: MemoryRepository) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    private fun MemoryFact.toInfo() = MemoryFactInfo(id, category, fact, confidence, extractedDate)
    private fun com.unuslumen.app.domain.memory.Conversation.toInfo() = ConversationInfo(id, title, createdDate, updatedDate, messageCount)
    private fun ConversationMessage.toInfo() = MessageSearchHit(id, conversationId, role, content, timestamp)

    /**
     * Read one fact by exact id, or return the error result explaining why not.
     *
     * Returns a Pair of (fact, null) on success and (null, errorResult) on
     * failure, so the two call sites below cannot accidentally treat a missing
     * row as a present one.
     */
    private suspend fun resolveFactOrError(factId: String): Pair<MemoryFact?, ToolExecutionResult?> {
        if (factId.isBlank()) {
            return null to ToolExecutionResult.error("Missing 'factId'")
        }
        if (!isBrainId(factId)) {
            return null to ToolExecutionResult.error(
                "Not a memory id: $factId. " +
                    "Use the 64-character id from an addMemoryFact or searchMemoryFacts result."
            )
        }
        val known = memoryRepository.getFact(factId)
        return if (known == null) {
            null to ToolExecutionResult.error("Fact not found: $factId")
        } else {
            known to null
        }
    }

    /** A real brain id: 64 lowercase hex characters. */
    private fun isBrainId(candidate: String): Boolean =
        candidate.length == 64 && candidate.all { ch -> ch in "0123456789abcdef" }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        MemoryToolDefinitions.SEARCH_MEMORY_FACTS -> {
            val query = args["query"] as? String ?: ""
            val category = args["category"] as? String
            val facts = if (query.isNotBlank()) {
                memoryRepository.searchFacts(query, category)
            } else if (category != null) {
                memoryRepository.getFactsByCategory(category)
            } else {
                memoryRepository.getAllFacts()
            }
            val result = MemoryFactsResult(facts.map { it.toInfo() })
            ToolExecutionResult.success(result, json.encodeToString(MemoryFactsResult.serializer(), result))
        }

        MemoryToolDefinitions.ADD_MEMORY_FACT -> {
            val fact = args["fact"] as? String ?: return ToolExecutionResult.error("Missing 'fact'")
            val category = args["category"] as? String ?: return ToolExecutionResult.error("Missing 'category'")
            val confidence = (args["confidence"] as? Number)?.toFloat() ?: 0.9f
            // The draft carries NO id of its own choosing. Whatever it holds is
            // replaced by the brain's deterministic id on the way out, and the
            // empty string makes it obvious that nothing here invented one.
            val draft = MemoryFact(
                id = "",
                category = category,
                fact = fact,
                confidence = confidence.coerceIn(0f, 1f),
                extractedDate = System.currentTimeMillis(),
                lastRecalledDate = System.currentTimeMillis(),
            )
            try {
                val stored = persistedFactRoundTrip(memoryRepository, draft)
                val result = MemoryFactResult(stored.toInfo())
                ToolExecutionResult.success(result, json.encodeToString(MemoryFactResult.serializer(), result))
            } catch (e: Exception) {
                ToolExecutionResult.error("brain store rejected: ${e.message?.take(200)}")
            }
        }

        MemoryToolDefinitions.LIST_MEMORY_FACTS -> {
            val category = args["category"] as? String
            try {
                val listing = memoryRepository.listFacts(category)
                if (!listing.brainReachable) {
                    return ToolExecutionResult.error(
                        "Memory is not running, so nothing can be listed right now."
                    )
                }
                val result = MemoryListingResult(
                    facts = listing.facts.map { it.toInfo() },
                    shown = listing.facts.size,
                    // The totals are separate fields rather than implied by the
                    // row count: a listing that reported its own length as the
                    // total would claim the brain remembers less than it does.
                    totalLive = listing.totalLive,
                    totalMatching = listing.totalMatching,
                )
                ToolExecutionResult.success(result, json.encodeToString(MemoryListingResult.serializer(), result))
            } catch (e: Exception) {
                ToolExecutionResult.error("brain listing rejected: ${e.message?.take(200)}")
            }
        }

        MemoryToolDefinitions.SEARCH_CONVERSATIONS -> {
            val query = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'")
            if (query.trim().length < 3) return ToolExecutionResult.error("Query too short. Use at least 3 characters.")
            val conversations = memoryRepository.searchConversations(query.trim())
            val result = SearchConversationsResult(conversations.take(20).map { it.toInfo() })
            ToolExecutionResult.success(result, json.encodeToString(SearchConversationsResult.serializer(), result))
        }

        MemoryToolDefinitions.GET_CONVERSATION_THREAD -> {
            val threadId = args["threadId"] as? String ?: return ToolExecutionResult.error("Missing 'threadId'")
            val conversation = memoryRepository.getConversation(threadId)
                ?: return ToolExecutionResult.error("Conversation not found: $threadId")
            val messages = memoryRepository.getMessagesByConversation(threadId)
            val result = ConversationWithMessagesResult(conversation.toInfo(), messages.map { it.toInfo() })
            ToolExecutionResult.success(result, json.encodeToString(ConversationWithMessagesResult.serializer(), result))
        }

        MemoryToolDefinitions.DELETE_MEMORY_FACT -> {
            val factId = args["factId"] as? String ?: return ToolExecutionResult.error("Missing 'factId'")
            // Exact lookup, not a listing scan. The scan was the defect: it only
            // saw rows sharing a word with the literal query "memory".
            val (known, lookupError) = resolveFactOrError(factId)
            if (lookupError != null) return lookupError
            try {
                memoryRepository.deleteFact(factId)
                val result = DeleteMemoryFactResult(factId, known!!.fact)
                ToolExecutionResult.success(result, json.encodeToString(DeleteMemoryFactResult.serializer(), result))
            } catch (e: Exception) {
                ToolExecutionResult.error("brain delete rejected: ${e.message?.take(200)}")
            }
        }

        MemoryToolDefinitions.UPDATE_MEMORY_FACT -> {
            val factId = args["factId"] as? String ?: return ToolExecutionResult.error("Missing 'factId'")
            val (known, lookupError) = resolveFactOrError(factId)
            if (lookupError != null) return lookupError
            val existing = known!!
            val now = System.currentTimeMillis()
            val updatedContent = (args["fact"] as? String) ?: existing.fact
            val updatedCategory = (args["category"] as? String) ?: existing.category
            val updatedConfidence = (args["confidence"] as? Number)?.toFloat() ?: existing.confidence
            val replacement = existing.copy(
                fact = updatedContent,
                category = updatedCategory,
                confidence = updatedConfidence.coerceIn(0f, 1f),
                extractedDate = now,
                lastRecalledDate = now,
            )
            try {
                val stored = persistedFactRoundTrip(memoryRepository, replacement)
                // Content-addressed identity: an edit produces a new content id,
                // so the row holding the OLD content is archived. Best effort
                // because the new row is already live and correct; a failure
                // here leaves a stale copy, not a lost edit.
                if (existing.id != stored.id) {
                    runCatching { memoryRepository.deleteFact(existing.id) }
                }
                val result = MemoryFactResult(stored.toInfo())
                ToolExecutionResult.success(result, json.encodeToString(MemoryFactResult.serializer(), result))
            } catch (e: Exception) {
                ToolExecutionResult.error("brain update rejected: ${e.message?.take(200)}")
            }
        }

        MemoryToolDefinitions.SEARCH_ALL_MEMORY -> {
            val query = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'")
            val facts = memoryRepository.searchFacts(query, null)
            val messages = memoryRepository.searchMessages(query)
            val toolResults = memoryRepository.searchToolResults(query)
            val result = UnifiedSearchResult(
                facts.map { it.toInfo() },
                messages.map { it.toInfo() },
                toolResults.map { ToolResultHit(it.id, it.toolName, it.resultText, it.timestamp, it.conversationId) },
            )
            ToolExecutionResult.success(result, json.encodeToString(UnifiedSearchResult.serializer(), result))
        }

        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }
}
