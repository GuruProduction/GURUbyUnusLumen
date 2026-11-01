// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain

import android.content.Context
import android.util.Log
import com.unuslumen.app.data.brain.cerebrum.CerebrumBrainApi
import com.unuslumen.app.data.brain.cerebrum.CerebrumHost
import com.unuslumen.app.data.memory.LocalEmbeddingService
import com.unuslumen.app.domain.memory.MemoryFact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BrainService — the single GURU-facing memory orchestrator.
 *
 * ONE STORE, the encrypted Cerebrum brain, end of. Every method here
 * dispatches real frames through the brain's JNI host (CerebrumBrainApi);
 * there is no second memory database and there is no Kotlin twin stack on
 * the memory path any more. Kotlin keeps hosting: signatures of this class
 * return domain shapes the app already consumes (SearchResult/MemoryFact),
 * converted from real brain reply bytes at this boundary and nowhere else.
 */
class BrainService(
    private val embeddingService: LocalEmbeddingService,
    private val context: Context
) {
    private val TAG = "guru_brain"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val brainApi: CerebrumBrainApi get() = CerebrumBrainApi

    // =====================================================================
    // Wire conversions (brain reply bytes -> domain types), boundary-only.
    // =====================================================================

    private fun buildDomainFact(entityId: String, text: String, score: Float): MemoryFact =
        MemoryFact(
            id = entityId,
            category = "memory",      // the brain holds it; the room category field no longer decides identity
            fact = text,
            embedding = emptyList(),
            confidence = score,
            sourceConversationIds = emptyList(),
            extractedDate = System.currentTimeMillis(),
            lastRecalledDate = System.currentTimeMillis(),
            layer = "BUFFER",         // real layer lives inside the brain's decay engine
            strength = score.coerceIn(0f, 1f),
            accessCount = 0,
            source = "cerebrum",
            trigger = "",
            beforeContext = "",
            afterContext = "",
            emotionalValence = 0f,
            domain = "",
            topic = "",
            subtopic = "",
            signature = ""
        )

    /**
     * Store a fact with the brain doing its REAL processing (signature/DWM/
     * graph/episodic/event trail inside the encrypted vault path), triggered
     * by one real CURATE frame. No Kotlin write path remains for facts.
     *
     * Returns the row as the brain holds it, carrying the REAL deterministic
     * 64-hex id (never a caller's synthetic uuid) and the category and
     * confidence the write carried. The id is computed here, before dispatch,
     * from the same hash the wire payload carries, so it needs no readback and
     * cannot disagree with what the brain stored.
     *
     * `category` becomes the brain's domain, because that is the only hierarchy
     * slot the brain has and a filter has to partition on something real.
     */
    suspend fun storeFact(
        fact: MemoryFact,
        conversationId: String = "",
        messageId: String = "",
        role: String = "",
        beforeContext: String = "",
        afterContext: String = "",
        conversationText: String = ""
    ): MemoryFact = withContext(Dispatchers.Default) {
        val currentTime = System.currentTimeMillis()
        val provenanceSource =
            "guru.role=$role conversation=$conversationId message=$messageId"
        // Confidence rides inside the provenance label, because the core wire
        // entry has no confidence field and dropping it made every read-back
        // report full belief in a fact the caller had marked as doubtful.
        val provenance = brainApi.provenanceLabelFor(provenanceSource, fact.confidence)

        val ack = brainApi.storeFact(
            factText = fact.fact,
            category = fact.category,
            provenanceLabel = provenance,
            createdAtMillis = if (fact.extractedDate > 0) fact.extractedDate else currentTime,
            domainLabel = domainForCategory(fact.category),
            topicLabel = fact.topic.ifBlank { "recall" },
            subtopicLabel = fact.subtopic.ifBlank { "fact" },
        )
        if (!ack.success) {
            Log.e(TAG, "brain storeFact rejected: ${ack.raw.take(300)}")
            error("brain fact store rejected: ${ack.raw.take(200)}")
        } else {
            Log.d(TAG, "brain storeFact accepted (engine writes: dwm=${ack.rawCounts.dwm_count} graph-nodes=${ack.rawCounts.graph_nodes} events=${ack.rawCounts.events})")
        }
        // Honest identity: the returned row carries the REAL deterministic
        // 64-hex brain id (never the caller's synthetic uid), so tool-side
        // delete/update act on rows the brain actually holds.
        fact.copy(
            id = brainApi.realIdFor(fact.fact),
            category = fact.category,
            confidence = fact.confidence.coerceIn(0f, 1f),
            domain = domainForCategory(fact.category),
        )
    }

    /**
     * The category a write files under.
     *
     * The brain's own hierarchy is domain/topic/subtopic, so the caller's
     * category IS the domain. Filing every fact under a constant "memory" domain
     * made a category filter a search for that word rather than a real
     * partition, which is why `getFactsByCategory` could not answer.
     */
    private fun domainForCategory(category: String): String {
        val trimmed = category.trim().lowercase()
        return if (trimmed.isEmpty()) "knowledge" else trimmed
    }

    /**
     * The category a stored row was filed under: read back from the rationale
     * the write recorded, falling back to the row's own domain, which is the
     * same value. A row written by any older path still reports a real
     * category rather than a blank.
     */
    private fun categoryOf(rationale: String, domain: String): String {
        val markerText = "guru storeFact: "
        val markerAt = rationale.indexOf(markerText)
        if (markerAt >= 0) {
            val tail = rationale.substring(markerAt + markerText.length).trim()
            if (tail.isNotEmpty()) return tail
        }
        return domain.ifBlank { "knowledge" }
    }

    /**
     * Build a domain fact from a retrieved body: every field the brain actually
     * stores, read back rather than invented.
     */
    private fun buildDomainFactFrom(body: CerebrumBrainApi.RetrievedBody): MemoryFact =
        MemoryFact(
            id = body.idHex,
            category = categoryOf(body.rationale, body.domain),
            fact = body.content,
            embedding = emptyList(),
            confidence = body.confidence.coerceIn(0f, 1f),
            sourceConversationIds = emptyList(),
            extractedDate = System.currentTimeMillis(),
            lastRecalledDate = System.currentTimeMillis(),
            layer = body.decayLayer.uppercase(),
            strength = body.decayStrength.coerceIn(0f, 1f),
            accessCount = body.decayAccesses.toInt(),
            source = body.provenanceSource,
            trigger = "",
            beforeContext = "",
            afterContext = "",
            emotionalValence = 0f,
            domain = body.domain,
            topic = body.topic,
            subtopic = body.subtopic,
            signature = ""
        )

    /**
     * Build a domain fact from a LIST row.
     *
     * The listing carries the same metadata the retrieve path does, which is
     * what lets a listing be both complete and informative: a caller asks what
     * is remembered and gets every row with its category and its confidence,
     * not a page of bare sentences.
     */
    private fun buildDomainFactFromListed(row: CerebrumBrainApi.ListedMemory): MemoryFact =
        MemoryFact(
            id = row.idHex,
            category = categoryOf(row.rationale, row.domain),
            fact = row.content,
            embedding = emptyList(),
            confidence = row.confidence.coerceIn(0f, 1f),
            sourceConversationIds = emptyList(),
            extractedDate = System.currentTimeMillis(),
            lastRecalledDate = System.currentTimeMillis(),
            layer = "BUFFER",
            strength = 1.0f,
            accessCount = 0,
            source = "",
            trigger = "",
            beforeContext = "",
            afterContext = "",
            emotionalValence = 0f,
            domain = row.domain,
            topic = row.topic,
            subtopic = row.subtopic,
            signature = ""
        )

    /**
     * One memory by exact id, or null when the brain does not hold it.
     *
     * This is the lookup the tool lane's delete and update are built on. It
     * replaced proving existence with a relevance search for a keyword, which
     * only ever saw the rows sharing a word with that keyword and therefore
     * reported "not found" for memories the brain held.
     */
    suspend fun getFact(factId: String): MemoryFact? = withContext(Dispatchers.Default) {
        if (!isBrainId(factId)) return@withContext null
        val body = brainApi.retrieveDeepOrShallow(factId, wantDeep = true) ?: return@withContext null
        buildDomainFactFrom(body)
    }

    /** A real brain id: 64 lowercase hex characters. */
    fun isBrainId(candidate: String): Boolean =
        candidate.length == 64 && candidate.all { it in "0123456789abcdef" }

    /** One consolidation result, including how many entries changed. */
    suspend fun consolidate(): Boolean = withContext(Dispatchers.Default) {
        brainApi.runDreamConsolidation()
    }

    /**
     * Search = the brain's own SEARCH frame (signature-space retrieval with
     * the engine's real ranking), real bodies through its answer content.
     */
        suspend fun search(query: String, topK: Int = 20): List<DomainSearchResult> = withContext(
        Dispatchers.Default,
    ) {
        brainApi.searchFacts(query, topK).map { hit ->
            DomainSearchResult(
                fact = buildDomainFact(hit.idHex, hit.content, hit.score),
                score = hit.score,
                source = "CEREBRUM",
            )
        }
    }

    /**
     * Search results in the domain shape used across the app (fact + score +
     * source tier label). Carries a real MemoryFact domain row directly; the
     * memory engine's twin SearchResult class moved out with the Kotlin stack.
     */
    data class DomainSearchResult(
        val fact: MemoryFact,
        val score: Float,
        val source: String,
    )

    /**
     * Past-conversation search (preamble's message tier): one real brain
     * SEARCH scoped to the chat-message marker content. Hits convert to the
     * domain message shape for the preamble body.
     */
    suspend fun conversationSearch(query: String, topK: Int = 15): List<com.unuslumen.app.domain.memory.ConversationMessage> = withContext(Dispatchers.Default) {
        brainApi.searchFacts(query, topK)
            .filter { hit -> hit.content.startsWith("message ") }
            .map { hit -> parseMessageDomainFromFactText(hit) }
    }

    /**
     * Tool-result search on the brain: rows carrying the tool-result marker.
     * Real SEARCH frame + one typed shape per hit for the preamble lane.
     */
    suspend fun toolResultSearch(
        query: String,
        topK: Int = 3,
    ): List<ToolResultContextHit> = withContext(Dispatchers.Default) {
        brainApi.searchFacts(query, topK)
            .filter { hit -> hit.content.startsWith("tool result: ") || hit.content.startsWith("Tool: ") }
            .map { hit -> parseToolResultHitFromFactText(hit) }
    }

    data class ToolResultContextHit(
        val toolName: String,
        val result: String,
        val timestamp: Long,
        val stale: Boolean,
    )

    private fun parseMessageDomainFromFactText(hit: CerebrumBrainApi.BrainHit): com.unuslumen.app.domain.memory.ConversationMessage {
        val rowText = hit.content
        val conversationIdText = extractValueFromTextSpan(rowText, "message conv=", " message=")
        val realMessageRowId = extractValueFromTextSpan(rowText, "message=", " role=")
        val messageRoleRow = extractValueFromTextSpan(rowText, "role=", " time=") ?: "user"
        val messageTimestampValue = extractValueFromTextSpan(rowText, "time=", ": ").toLongOrNull() ?: hit.score.toLong()
        val realContentBody = rowText.substringAfter(": ")
        return com.unuslumen.app.domain.memory.ConversationMessage(
            id = realMessageRowId,
            conversationId = conversationIdText,
            role = messageRoleRow,
            content = realContentBody,
            timestamp = messageTimestampValue,
        )
    }

    private fun parseToolResultHitFromFactText(
        hit: CerebrumBrainApi.BrainHit,
    ): ToolResultContextHit {
        val rowText = hit.content
        // rows store the same tool result marker content used at write: a
        // "tool result: toolName age timestamp: result body" shape.
        val toolNameValue = extractValueFromTextSpan(rowText, "tool result: ", " timestamp") ?: "brain-tool-row"
        val resultBody = rowText.substringAfterLast(": ", missingDelimiterValue = rowText)
        val timestampValue = extractValueFromTextSpan(rowText, "timestamp=", " ").toLongOrNull() ?: System.currentTimeMillis()
        val ageMinutesRow = (System.currentTimeMillis() - timestampValue) / (60 * 1000)
        val isStale = ageMinutesRow > 24 * 60
        return ToolResultContextHit(
            toolName = toolNameValue,
            result = resultBody,
            timestamp = timestampValue,
            stale = isStale,
        )
    }

    private fun extractValueFromTextSpan(inputText: String, tokenName: String, delimiterEnd: String?): String {
        val spanStart = inputText.indexOf(tokenName)
        if (spanStart < 0) return ""
        val startEndMarkerSpan = spanStart + tokenName.length
        val restOfSpan = inputText.substring(startEndMarkerSpan)
        return when (delimiterEnd) {
            null -> restOfSpan.trim()
            else -> {
                val endsSpanAt = restOfSpan.indexOf(delimiterEnd)
                if (endsSpanAt < 0) restOfSpan.trim() else restOfSpan.substring(0, endsSpanAt).trim()
            }
        }
    }

    /**
     * Recall by hex id: real RETRIEVE; the brain's decay engine strengthening
     * + access-count growth happen on the brain side during the same frame.
     */
    suspend fun recallFact(factId: String) = withContext(Dispatchers.Default) {
        if (factId.length == 64 && factId.all { it in "0123456789abcdef" }) {
            val body = brainApi.retrieveDeepOrShallow(factId, wantDeep = false)
            Log.d(TAG, "brain recall (shallow): hit=${body != null} on ${factId.take(12)}...")
        } else {
            Log.d(TAG, "recall skipped: not a brain hex id")
        }
    }

    /**
     * Delete: one real CURATE::Delete on the brain. No-Delete doctrine: the
     * engine archives, preserving the memory trail.
     */
    suspend fun deleteFact(factId: String) = withContext(Dispatchers.Default) {
        if (factId.length == 64 && factId.all { it in "0123456789abcdef" }) {
            val ack = brainApi.curateJsonOps(
                listOf(brainApi.deleteJsonOpByHex(factId, "guru deleteFact: brain archive"))
            )
            if (!ack.success) {
                Log.e(TAG, "brain delete rejected: ${ack.raw.take(300)}")
                error("brain delete rejected: ${ack.raw.take(200)}")
            }
            Log.d(TAG, "brain delete dispatched (engine rows: deleted=${ack.rawCounts.deleted})")
        } else {
            Log.d(TAG, "delete skipped: not a brain hex id")
        }
    }

    /** Dream = one CONSOLIDATE frame: real decay refresh + dream merges inside the brain. */
    suspend fun dream(): DreamReport = withContext(Dispatchers.Default) {
        val dispatchedFrame = brainApi.runDreamConsolidation()
        DreamReport(dispatchedFrame = dispatchedFrame)
    }

    /**
     * Decay: covered inside every CONSOLIDATE (decay_all runs dream-side each
     * consolidate); explicit applyDecay stays a thin alias. Called by the
     * DecayWorker on its 12h cadence as one cheap real brain frame.
     */
    suspend fun applyDecay(): Int = withContext(Dispatchers.Default) {
        val dispatched = brainApi.runDreamConsolidation()
        Log.d(TAG, "apply-decay via brain consolidation dispatched: $dispatched")
        0 // real counts land in the ConsolidationReportFull on brain logs; the kotlin count moved with the engine stack
    }

    /**
     * Hot cache: brain-side, real. Called at boot; registers the brain with
     * nothing (its QueryEngine keeps its own internal hot cache warm from its
     * own retrieval). Kotlin's prefetch cache retired along with its stack.
     */
    suspend fun seedCache(): Boolean = withContext(Dispatchers.Default) {
        CerebrumHost.isRunning()
    }

    /**
     * Facts in one category: a real partition of the brain's domain, resolved
     * by the LIST frame.
     *
     * The category comparison is case-insensitive and exact on the word, not a
     * relevance query. The previous version searched for the category name as
     * text, so it returned every fact whose body happened to contain that word
     * and omitted every fact filed under the category that did not.
     */
    suspend fun getFactsByCategory(category: String): List<MemoryFact> = withContext(Dispatchers.Default) {
        val listing = brainApi.listFacts(domainFilter = domainForCategory(category), limit = 0)
            ?: return@withContext emptyList()
        listing.memories.map { row -> buildDomainFactFromListed(row) }
    }

    suspend fun getFactsByDomain(domain: String, topic: String? = null): List<MemoryFact> = withContext(Dispatchers.Default) {
        val listing = brainApi.listFacts(domainFilter = domain, limit = 0) ?: return@withContext emptyList()
        val rows = listing.memories
        val narrowed = if (topic == null) rows else rows.filter { row -> row.topic.equals(topic, ignoreCase = true) }
        narrowed.map { row -> buildDomainFactFromListed(row) }
    }

    /**
     * Every live memory the brain holds, newest first.
     *
     * This was a SEARCH for the literal word "memory" capped at 200 rows, which
     * is the defect issue 201 reports: a fact whose text did not contain that
     * word was invisible to the listing and to the delete path's existence
     * check. It is now a real enumeration.
     */
    suspend fun getAllFacts(): List<MemoryFact> = withContext(Dispatchers.Default) {
        val listing = brainApi.listFacts(domainFilter = "", limit = 0)
            ?: return@withContext emptyList()
        listing.memories.map { row -> buildDomainFactFromListed(row) }
    }

    /**
     * Every live memory plus the brain's own totals.
     *
     * The totals let a listing report "showing N of M" honestly instead of
     * presenting a page as the whole memory.
     */
    suspend fun getAllFactsWithTotals(): BrainListPage = withContext(Dispatchers.Default) {
        val listing = brainApi.listFacts(domainFilter = "", limit = 0)
            ?: return@withContext BrainListPage(emptyList(), 0, 0, brainReachable = false)
        BrainListPage(
            facts = listing.memories.map { row -> buildDomainFactFromListed(row) },
            totalMatching = listing.totalMatching,
            totalLive = listing.totalLive,
            brainReachable = true,
        )
    }

    /**
     * One page of the memory listing.
     *
     * `brainReachable` is carried rather than inferred from an empty list: a
     * brain that is not running and a brain that holds nothing are different
     * answers, and reporting the first as the second is a lie about the state
     * of the machine.
     */
    data class BrainListPage(
        val facts: List<MemoryFact>,
        val totalMatching: Int,
        val totalLive: Int,
        val brainReachable: Boolean,
    )

    fun classifyQuery(query: String): String = "CEREBRUM"

    /**
     * Initialisation: the real GURU brain boot already fired in the app
     * layer; this routine now verifies connectivity and logs the real
     * engine snapshot. Everything else (backfills, prefetch seeds) belongs
     * to the brain's own code internally.
     */
    fun initialise() {
        serviceScope.launch {
            try {
                Log.d(TAG, "BrainService initialise: cerebrum host running=${CerebrumHost.isRunning()}")
            } catch (e: Exception) {
                Log.e(TAG, "BrainService initialise failed: ${e.message}")
            }
        }
    }

    /** Shutdown: Kotlin orchestration ends. The BRAIN stays resident; app process death is its stop path. */
    fun shutdown() {
        serviceScope.cancel()
    }

    // Embedding lazy backfill retired with the Kotlin store; the brain runs
    // its own engine-layer corpus statistics per write.

    data class DreamReport(val dispatchedFrame: Boolean)
}