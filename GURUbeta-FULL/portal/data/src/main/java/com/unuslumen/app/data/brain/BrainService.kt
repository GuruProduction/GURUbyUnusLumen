package com.unuslumen.app.data.brain

import android.content.Context
import android.util.Log
import com.unuslumen.app.database.dao.MemoryCrossReferenceDao
import com.unuslumen.app.database.dao.MemoryEdgeDao
import com.unuslumen.app.database.dao.MemoryEventDao
import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.entity.MemoryFactEntity
import com.unuslumen.app.data.memory.LocalEmbeddingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BrainService — Central orchestrator for all 11 brain engines.
 *
 * Single entry point for the brain system. The rest of the app talks to
 * BrainService, not to individual engines.
 *
 * Phase 3 of the Brain Plan.
 */
class BrainService(
    private val memoryFactDao: MemoryFactDao,
    private val memoryEdgeDao: MemoryEdgeDao,
    private val memoryEventDao: MemoryEventDao,
    private val memoryCrossReferenceDao: MemoryCrossReferenceDao,
    private val embeddingService: LocalEmbeddingService,
    private val context: Context
) {
    private val TAG = "guru_brain"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Engine initialisation — no circular dependencies.
    // PrefetchEngine does not depend on QueryEngine.
    // QueryEngine does not depend on PrefetchEngine.
    // BrainService coordinates between them.
    private val signatureEngine = SignatureEngine

    private val embeddingEngine by lazy { EmbeddingEngine(embeddingService) }

    private val decayEngine by lazy { DecayEngine(memoryFactDao) }

    private val graphEngine by lazy { GraphEngine(memoryEdgeDao) }

    private val episodicEngine = EpisodicEngine

    private val curationEngine by lazy { CurationEngine(memoryCrossReferenceDao) }

    private val eventStore by lazy { EventStore(memoryEventDao) }

    private val policyEngine = PolicyEngine()

    private val prefetchEngine by lazy {
        PrefetchEngine(
            graphEngine = graphEngine,
            memoryFactDao = memoryFactDao,
            context = context
        )
    }

    private val queryEngine by lazy {
        QueryEngine(
            memoryFactDao = memoryFactDao,
            graphEngine = graphEngine,
            signatureEngine = signatureEngine,
            policyEngine = policyEngine,
            prefetchEngine = null
        )
    }

    private val dreamEngine by lazy {
        DreamEngine(
            memoryFactDao = memoryFactDao,
            crossReferenceDao = memoryCrossReferenceDao,
            graphEngine = graphEngine,
            decayEngine = decayEngine
        )
    }

    /**
     * Store a new fact with full brain processing.
     */
    suspend fun storeFact(
        fact: MemoryFactEntity,
        conversationId: String = "",
        messageId: String = "",
        role: String = "",
        beforeContext: String = "",
        afterContext: String = "",
        conversationText: String = ""
    ): MemoryFactEntity = withContext(Dispatchers.Default) {

        val currentTime = System.currentTimeMillis()

        // 1. Generate signature
        val signature = signatureEngine.generateSignature(fact.fact)

        // 2. Classify into domain/topic/subtopic
        val curation = curationEngine.classify(fact.fact, fact.category)

        // 3. Record episodic context
        val episodicContext = episodicEngine.buildEpisodicContext(
            source = if (role == "user") EpisodicEngine.SOURCE_USER_STATEMENT
                    else if (role == "assistant") EpisodicEngine.SOURCE_ASSISTANT_OBSERVATION
                    else EpisodicEngine.SOURCE_INFERENCE,
            trigger = EpisodicEngine.TRIGGER_EXPLICIT,
            beforeContext = beforeContext,
            afterContext = afterContext,
            conversationText = conversationText.ifBlank { fact.fact }
        )

        // 4. Build enriched fact
        val enrichedFact = fact.copy(
            signature = signature,
            layer = "BUFFER",
            strength = 1.0f,
            accessCount = 0,
            source = episodicContext.source,
            trigger = episodicContext.trigger,
            beforeContext = episodicContext.beforeContext,
            afterContext = episodicContext.afterContext,
            emotionalValence = episodicContext.emotionalValence,
            domain = curation.domain,
            topic = curation.topic,
            subtopic = curation.subtopic
        )

        // 5. Store the fact
        memoryFactDao.insertFact(enrichedFact)

        // 6. Generate embedding asynchronously
        launchEmbeddingGeneration(enrichedFact.id, fact.fact)

        // 7. Build cross-references and semantic edges (single getAllFacts call)
        val existingFacts = memoryFactDao.getAllFacts().filter { it.id != enrichedFact.id }
        curationEngine.buildCrossReferences(enrichedFact, existingFacts, currentTime)
        graphEngine.buildSemanticEdges(enrichedFact, existingFacts, currentTime)

        // 8. Record event
        eventStore.recordEvent(
            conversationId = conversationId,
            messageId = messageId,
            role = role,
            content = fact.fact,
            currentTime = currentTime
        )

        Log.d(TAG, "Stored fact ${enrichedFact.id}: domain=${curation.domain} topic=${curation.topic} valence=${episodicContext.emotionalValence}")

        enrichedFact
    }

    /**
     * Search for facts. Runs the four-tier retrieval, then caches results
     * in the prefetch hot cache and boosts scores for cached facts.
     */
    suspend fun search(query: String, topK: Int = 20): List<SearchResult> = withContext(Dispatchers.Default) {
        val engineResults = queryEngine.search(query, topK)

        // Cache search results in prefetch engine for future hot cache hits
        prefetchEngine.cacheSearchResults(engineResults, System.currentTimeMillis())

        // Lazy embedding backfill — generate embeddings for returned facts that don't have them
        for (result in engineResults) {
            if (result.fact.embedding.isEmpty()) {
                launchEmbeddingGeneration(result.fact.id, result.fact.fact)
            }
        }

        // Boost scores for facts already in the hot cache
        val cachedIds = prefetchEngine.getCachedFacts().map { it.id }.toSet()
        if (cachedIds.isNotEmpty()) {
            engineResults.map { result ->
                if (result.fact.id in cachedIds) {
                    result.copy(score = result.score * 1.2f)
                } else {
                    result
                }
            }
        } else {
            engineResults
        }
    }

    /**
     * Recall a fact by ID. Boosts strength, increments access count, checks promotion.
     * Also triggers lazy embedding backfill if the fact has no embedding.
     */
    suspend fun recallFact(factId: String) = withContext(Dispatchers.Default) {
        val fact = memoryFactDao.getFact(factId) ?: return@withContext
        decayEngine.onRecall(factId, System.currentTimeMillis())
        prefetchEngine.addToCache(fact, System.currentTimeMillis(), "recalled")

        // Lazy embedding backfill — generate one at a time in background
        if (fact.embedding.isEmpty()) {
            launchEmbeddingGeneration(fact.id, fact.fact)
        }
    }

    /**
     * Delete a fact and clean up all its edges and cross-references.
     */
    suspend fun deleteFact(factId: String) = withContext(Dispatchers.Default) {
        memoryFactDao.deleteFact(factId)
        memoryEdgeDao.deleteEdgesForFact(factId)
        memoryCrossReferenceDao.deleteCrossReferencesFor(factId)
        prefetchEngine.clearFromCache(factId)
        Log.d(TAG, "Deleted fact $factId and cleaned up edges/cross-refs")
    }

    /**
     * Run dream consolidation. Call when the user is idle or asleep.
     */
    suspend fun dream(): DreamReport = withContext(Dispatchers.Default) {
        val report = dreamEngine.dream(System.currentTimeMillis())
        Log.d(TAG, "Dream complete: pruned=${report.prunedCount} merged=${report.mergedCount} promoted=${report.promotedCount} edges=${report.edgesBuilt} sigs=${report.signaturesGenerated}")
        report
    }

    /**
     * Apply decay to all facts. Call periodically.
     */
    suspend fun applyDecay(): Int = withContext(Dispatchers.Default) {
        decayEngine.applyDecayAll(System.currentTimeMillis())
    }

    /**
     * Seed the prefetch cache on session start.
     */
    suspend fun seedCache() = withContext(Dispatchers.Default) {
        prefetchEngine.seedCache(System.currentTimeMillis())
    }

    suspend fun getFactsByCategory(category: String): List<MemoryFactEntity> = withContext(Dispatchers.Default) {
        memoryFactDao.getFactsByCategory(category)
    }

    suspend fun getFactsByDomain(domain: String, topic: String? = null): List<MemoryFactEntity> = withContext(Dispatchers.Default) {
        if (topic != null) {
            memoryFactDao.getFactsByDomainTopic(domain, topic)
        } else {
            memoryFactDao.getFactsByDomain(domain)
        }
    }

    suspend fun getAllFacts(): List<MemoryFactEntity> = withContext(Dispatchers.Default) {
        memoryFactDao.getAllFacts()
    }

    fun classifyQuery(query: String): PolicyEngine.QueryClassification {
        return policyEngine.classifyQuery(query)
    }

    /**
     * Initialise the brain on app startup. Backfills old facts that were
     * stored before the brain was integrated. Runs in the service scope
     * so it does not block the UI or app startup.
     *
     * - Generates signatures for facts that do not have them
     * - Classifies domain/topic/subtopic for facts that do not have them
     * - Builds semantic edges between facts
     * - Seeds the prefetch cache
     *
     * Call this once from GuruApplication.onCreate() after BrainScheduler.schedule().
     */
    fun initialise() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Brain initialisation starting")


                val currentTime = System.currentTimeMillis()
                val allFacts = memoryFactDao.getAllFacts()

                // Backfill signatures and domain classification for old facts
                var backfilled = 0
                for (fact in allFacts) {
                    var updated = fact

                    if (fact.signature.isBlank()) {
                        updated = updated.copy(signature = signatureEngine.generateSignature(fact.fact))
                    }

                    if (fact.domain.isBlank()) {
                        val curation = curationEngine.classify(fact.fact, fact.category)
                        updated = updated.copy(
                            domain = curation.domain,
                            topic = curation.topic,
                            subtopic = curation.subtopic
                        )
                    }

                    if (updated != fact) {
                        memoryFactDao.insertFact(updated)
                        backfilled++
                    }
                }

                // NOTE: Embeddings are NOT bulk-backfilled on startup.
                // Bulk TFLite inference on startup freezes the device.
                // Embeddings are generated one at a time when new facts are stored
                // through storeFact() via launchEmbeddingGeneration().
                // Existing facts without embeddings get them lazily when
                // they are recalled or searched via the new lazy backfill in
                // recallFact() and search(). They are NOT generated all at once.

                // Build semantic edges for all facts that have signatures
                val factsWithSigs = allFacts.filter { it.signature.isNotBlank() }
                for (fact in factsWithSigs.take(50)) {
                    graphEngine.buildSemanticEdges(fact, factsWithSigs.filter { it.id != fact.id }, currentTime)
                }

                // Seed the prefetch cache
                seedCache()

                Log.d(TAG, "Brain initialisation complete: backfilled=$backfilled facts, edges built for ${factsWithSigs.size.coerceAtMost(50)} facts")
            } catch (e: Exception) {
                Log.e(TAG, "Brain initialisation failed: ${e.message}")
            }
        }
    }

    /**
     * Shutdown — cancel all background coroutines.
     */
    fun shutdown() {
        serviceScope.cancel()
    }

    /**
     * Launch embedding generation in the background using the service-scoped coroutine.
     */
    private fun launchEmbeddingGeneration(factId: String, factText: String) {
        serviceScope.launch {
            try {
                val embedding = embeddingEngine.generateEmbedding(factText)
                if (embedding.isNotEmpty()) {
                    Log.d(TAG, "Embedding stored for fact $factId (${embedding.size} dims)")
                    val fact = memoryFactDao.getFact(factId)
                    if (fact != null) {
                        memoryFactDao.insertFact(fact.copy(embedding = embedding))
                        Log.d(TAG, "Embedding generated and stored for fact $factId")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Embedding generation failed for fact $factId: ${e.message}")
            }
        }
    }
}
