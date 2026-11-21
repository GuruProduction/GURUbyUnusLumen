// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.agents.currentDispatchScope
import com.unuslumen.app.data.memory.persistedFactRoundTrip
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.memory.MemoryCategories
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryOwnership
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.repository.GuruAgentRepository
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
class MemoryToolExecutor(
    private val memoryRepository: MemoryRepository,
    /**
     * Used for ONE thing: resolving the calling agent's memory scope.
     *
     * The agent's identity arrives from the coroutine, and the scope is a property of the
     * agent's own row. Nothing else about the agent is read here, and the scope is never
     * taken from a tool argument, because a model that could name its own scope would have
     * no scope at all.
     */
    private val agentRepository: GuruAgentRepository? = null,
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The memory facts the caller may see, filtered at the point of return.
     *
     * THIS IS THE BOUNDARY, AND IT IS ONE FUNCTION SO EVERY READ AGREES.
     *
     * The agent's visibility scope used to be enforced in `AgentMemoryService.search` and
     * nowhere else. Every other route into memory went straight to the repository, so an
     * agent that called `searchMemoryFacts`, `listMemoryFacts`, `traverseMemoryGraph` or
     * `searchAllMemory` bypassed its own partition completely. An own-only agent could read
     * the whole vault, and another agent's private fact was one tool name away. A filter on
     * one entry point is a courtesy; a boundary is on every path out.
     *
     * A CALLER WITH NO AGENT IN SCOPE SEES EVERYTHING. That is the main assistant, whose
     * memory this all is: the partition exists to stop one agent reading another's, not to
     * lock the owner out of his own brain.
     *
     * A brain that is not running yields no rows before this point, so nothing here has to
     * invent an answer for that case.
     */
    private suspend fun visibleToCaller(facts: List<MemoryFact>): List<MemoryFact> {
        val scopeCarrier = currentDispatchScope()
        val callerName = scopeCarrier?.agentName
        if (callerName.isNullOrBlank()) return facts

        val scope: AgentMemoryScope = agentRepository?.resolveAgent(callerName)?.memoryScope
            // An agent named in scope that the repository cannot resolve is a state the
            // dispatcher cannot produce. The safe reading is an empty scope, not a full one:
            // a fact leaked to an unknown caller cannot be recalled.
            ?: AgentMemoryScope.NONE
        val callerId = agentRepository?.resolveAgent(callerName)?.id.orEmpty()

        return facts.filter { fact ->
            val inCategory = scope.categories.isEmpty() ||
                scope.categories.any { wanted -> wanted.equals(fact.category, ignoreCase = true) }
            if (!inCategory) return@filter false
            val provenance = MemoryOwnership.parse(fact.source)
            MemoryOwnership.mayRead(
                provenance = provenance,
                requestingAgentId = callerId,
                readOwn = scope.readOwn,
                readShared = scope.readShared,
            )
        }
    }

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
        if (known == null) {
            return null to ToolExecutionResult.error("Fact not found: $factId")
        }
        /*
         * AN EXISTENCE CHECK IS ALSO A READ.
         *
         * `deleteMemoryFact` and `updateMemoryFact` resolve a row by id, and that lookup
         * returns its text. Without this, an agent could read any fact in the vault simply by
         * naming its id, and the id is derivable from the content, so it is not a secret.
         *
         * It is reported as absent rather than refused. A refusal would confirm the row
         * exists, which tells a caller something about a fact it may not see.
         */
        if (visibleToCaller(listOf(known)).isEmpty()) {
            return null to ToolExecutionResult.error("Fact not found: $factId")
        }
        return known to null
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
            val result = MemoryFactsResult(visibleToCaller(facts).map { it.toInfo() })
            ToolExecutionResult.success(result, json.encodeToString(MemoryFactsResult.serializer(), result))
        }

        MemoryToolDefinitions.ADD_MEMORY_FACT -> {
            val fact = args["fact"] as? String ?: return ToolExecutionResult.error("Missing 'fact'")
            // THE CATEGORY IS CHECKED AGAINST THE ONE VOCABULARY (issue 485).
            //
            // It used to be taken as free text, so `user_preference`,
            // `user-preferences`, `preference` and `Preferences` became separate
            // categories holding one kind of fact, and the category filter found one
            // of them at a time. A known spelling is repaired onto the canonical
            // name; anything outside the set is refused with the set named, because
            // a filter over an open vocabulary is not a filter.
            val category = when (val normalised = MemoryCategories.normalise(args["category"] as? String)) {
                is MemoryCategories.Normalisation.Canonical -> normalised.category
                is MemoryCategories.Normalisation.Repaired -> normalised.category
                is MemoryCategories.Normalisation.Refused ->
                    return ToolExecutionResult.error(normalised.reason)
            }
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

        MemoryToolDefinitions.CORTEX_RECORD -> {
            val content = (args["content"] as? String)?.trim()
                ?: return ToolExecutionResult.error("Missing 'content'")
            if (content.isEmpty()) {
                return ToolExecutionResult.error("'content' must not be blank")
            }
            // Relation ids are checked HERE rather than at the wire, because a
            // malformed id dropped silently is a link the caller believes exists.
            // A refusal names the value it refused.
            val relationIds = (args["relatedTo"] as? String)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val malformed = relationIds.firstOrNull { candidate ->
                candidate.length != 64 || candidate.any { it !in "0123456789abcdefABCDEF" }
            }
            if (malformed != null) {
                return ToolExecutionResult.error(
                    "'relatedTo' contains \"$malformed\", which is not a 64-character memory id. " +
                        "Use the ids from searchMemoryFacts or searchAllMemory hits.",
                )
            }
            val confidence = (args["confidence"] as? Number)?.toFloat() ?: 0.9f
            try {
                val stored = memoryRepository.storeRecord(
                    content = content,
                    domain = (args["domain"] as? String).orEmpty(),
                    topic = (args["topic"] as? String).orEmpty(),
                    subtopic = (args["subtopic"] as? String).orEmpty(),
                    provenance = (args["provenance"] as? String).orEmpty(),
                    rationale = (args["rationale"] as? String).orEmpty(),
                    relatedTo = relationIds.map { it.lowercase() },
                    confidence = confidence.coerceIn(0f, 1f),
                )
                val result = MemoryFactResult(stored.toInfo())
                ToolExecutionResult.success(result, json.encodeToString(MemoryFactResult.serializer(), result))
            } catch (e: Exception) {
                ToolExecutionResult.error("brain record store rejected: ${e.message?.take(200)}")
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
                val permittedListing = visibleToCaller(listing.facts)
                val result = MemoryListingResult(
                    facts = permittedListing.map { it.toInfo() },
                    shown = permittedListing.size,
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
            // The same check on the way in, for the same reason: an update is how a
            // fact drifts into a new category, and a filter that cannot find the new
            // spelling is how a fact becomes unfindable (issue 485).
            val updatedCategory = when (val raw = args["category"] as? String) {
                null -> existing.category
                else -> when (val normalised = MemoryCategories.normalise(raw)) {
                    is MemoryCategories.Normalisation.Canonical -> normalised.category
                    is MemoryCategories.Normalisation.Repaired -> normalised.category
                    is MemoryCategories.Normalisation.Refused ->
                        return ToolExecutionResult.error(normalised.reason)
                }
            }
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

        MemoryToolDefinitions.TRAVERSE_MEMORY_GRAPH -> {
            val subject = args["subject"] as? String ?: return ToolExecutionResult.error("Missing 'subject'")
            if (subject.trim().length < 2) {
                return ToolExecutionResult.error("Subject too short. Name the person, project or thing to walk around.")
            }
            val edgeType = (args["edgeType"] as? String ?: "composite").trim().lowercase()
            val depth = (args["depth"] as? Number)?.toInt() ?: 3
            val maxNodes = (args["maxNodes"] as? Number)?.toInt() ?: 40
            try {
                val connected = memoryRepository.traverseGraph(subject.trim(), edgeType, depth, maxNodes)
                if (connected.isEmpty()) {
                    // An empty walk means the graph reached nothing from that
                    // subject, which is a real answer about the brain's shape and
                    // not a failure. It is reported as an empty list plus the
                    // subject echoed back, so a caller can tell it apart from a
                    // search that simply had no word matches.
                    val empty = TraverseMemoryGraphResult(subject.trim(), edgeType, depth.coerceIn(1, 6), emptyList())
                    return ToolExecutionResult.success(empty, json.encodeToString(TraverseMemoryGraphResult.serializer(), empty))
                }
                val result = TraverseMemoryGraphResult(
                    subject = subject.trim(),
                    edgeType = edgeType,
                    depth = depth.coerceIn(1, 6),
                    connected = visibleToCaller(connected).map { it.toInfo() },
                )
                ToolExecutionResult.success(result, json.encodeToString(TraverseMemoryGraphResult.serializer(), result))
            } catch (e: Exception) {
                ToolExecutionResult.error("brain graph walk rejected: ${e.message?.take(200)}")
            }
        }

        MemoryToolDefinitions.SEARCH_ALL_MEMORY -> {
            val query = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query'")
            val facts = memoryRepository.searchFacts(query, null)
            val messages = memoryRepository.searchMessages(query)
            val toolResults = memoryRepository.searchToolResults(query)
            val result = UnifiedSearchResult(
                visibleToCaller(facts).map { it.toInfo() },
                messages.map { it.toInfo() },
                toolResults.map { ToolResultHit(it.id, it.toolName, it.resultText, it.timestamp, it.conversationId) },
            )
            ToolExecutionResult.success(result, json.encodeToString(UnifiedSearchResult.serializer(), result))
        }

        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }
}
