// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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

    /**
     * Persist facts and return them as the brain stored them.
     *
     * The return value is load-bearing, not a convenience. The brain assigns its
     * own deterministic 64-hex id from the content, and that id is the only
     * handle any later delete or update can resolve. A persist that returned
     * nothing forced callers to invent an id or to search for one afterwards,
     * and both of those are why a fact could be written and then be impossible
     * to remove.
     */
    suspend fun persistFacts(facts: List<MemoryFact>): List<MemoryFact>

    /**
     * Write one record with its full place in the knowledge graph (issue 486).
     *
     * Separate from [persistFacts] because the two answer different questions.
     * `persistFacts` stores prose under a category, which is what every automatic
     * writer has. This stores a node: a domain, a topic, a subtopic, a rationale and
     * a set of typed relations to other memories. The brain accepts both; only the
     * tool layer was flattening the second into the first.
     */
    suspend fun storeRecord(
        content: String,
        domain: String,
        topic: String,
        subtopic: String,
        provenance: String,
        rationale: String,
        relatedTo: List<String>,
        confidence: Float,
    ): MemoryFact

    /** Every live fact the brain holds, newest first. */
    suspend fun getAllFacts(): List<MemoryFact>

    /**
     * One fact by its exact brain id, or null when the brain does not hold it.
     *
     * This is the existence check a delete or an update uses. Proving existence
     * with a relevance search reported "not found" for any memory that happened
     * not to share a word with the search string.
     */
    suspend fun getFact(factId: String): MemoryFact?

    /**
     * A listing page: the rows, the true totals, and whether the brain answered.
     *
     * `brainReachable` exists because an unreachable brain and an empty memory
     * are different facts about the machine, and a listing that reported the
     * first as the second would be lying to the person reading it.
     */
    suspend fun listFacts(category: String? = null): FactListing

    suspend fun getFactsByCategory(category: String): List<MemoryFact>
    suspend fun searchFacts(query: String, category: String? = null): List<MemoryFact>
    suspend fun markFactRecalled(factId: String)
    suspend fun deleteFact(factId: String)

    /**
     * Walk the graph around a subject and return the connected facts.
     *
     * A relevance search answers "which memories share words with this", and a
     * listing answers "what do I hold". Neither can answer "what is connected to
     * this", because the connection is an edge rather than a shared word. That
     * third question is what this exposes: typed edges (semantic, temporal,
     * causal, entity) traversed from whatever node best represents the subject.
     *
     * [edgeType] is one of semantic, temporal, causal, entity or composite, and
     * anything unrecognised falls back to composite rather than failing.
     */
    suspend fun traverseGraph(
        subject: String,
        edgeType: String = "composite",
        maxDepth: Int = 3,
        maxNodes: Int = 40,
    ): List<MemoryFact>

    suspend fun persistThreads(threads: List<ConversationThread>)
    suspend fun getAllThreads(): List<ConversationThread>

    /**
     * [conversationId] attributes the preamble's injections in the ledger. It
     * defaults to blank so existing callers compile unchanged; with a blank id
     * the preamble is built and nothing is recorded.
     */
    suspend fun buildContextPreamble(
        query: String,
        humanName: String = "",
        currentMessages: List<AiMessage> = emptyList(),
        conversationId: String = "",
    ): RetrievedContext
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
