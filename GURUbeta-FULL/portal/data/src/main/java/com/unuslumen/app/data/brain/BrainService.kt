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
        val reasoning = if (conversationText.isBlank()) "store (role=$role)" else "store ($role in $conversationId)"
        val provenance = "guru.role=$role conversation=$conversationId message=$messageId"

        val ack = brainApi.storeFact(
            factText = fact.fact,
            category = fact.category,
            provenanceLabel = provenance,
            createdAtMillis = if (fact.extractedDate > 0) fact.extractedDate else currentTime,
            topicLabel = fact.topic.ifBlank { "recall" },
            subtopicLabel = fact.subtopic.ifBlank { "fact" },
        )
        if (!ack.success) {
            Log.e(TAG, "brain storeFact rejected: ${ack.raw.take(300)}")
            error("brain fact store rejected: ${ack.raw.take(200)}")
        } else {
            Log.d(TAG, "brain storeFact accepted (engine writes: dwm=${ack.rawCounts.dwm_count} graph-nodes=${ack.rawCounts.graph_nodes} events=${ack.rawCounts.events})")
        }
        fact
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
     * Facts listing by category; real RETRIEVE-shaped via brain SEARCH with a
     * domain query (top-k covers it on small categories; deep filters land in
     * a follow-up wire pass through the engine's Query frame with a Composite
     * search shape).
     */
    suspend fun getFactsByCategory(category: String): List<MemoryFact> = withContext(Dispatchers.Default) {
        brainApi.searchFacts(category, 50).map { hit ->
            buildDomainFact(hit.idHex, hit.content, hit.score)
        }
    }

    suspend fun getFactsByDomain(domain: String, topic: String? = null): List<MemoryFact> = withContext(Dispatchers.Default) {
        val searchKey = if (topic == null) domain else "$domain $topic"
        brainApi.searchFacts(searchKey, 50).map { hit ->
            buildDomainFact(hit.idHex, hit.content, hit.score)
        }
    }

    /**
     * "All facts" through the same SEARCH surface (query "brain memory" topK
     * covers recent memories on device); full list surfaces follow in the
     * brain's RETRIEVE path battery (next wire pass: LIST frame).
     */
    suspend fun getAllFacts(): List<MemoryFact> = withContext(Dispatchers.Default) {
        brainApi.searchFacts("memory", 200).map { hit ->
            buildDomainFact(hit.idHex, hit.content, hit.score)
        }
    }

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