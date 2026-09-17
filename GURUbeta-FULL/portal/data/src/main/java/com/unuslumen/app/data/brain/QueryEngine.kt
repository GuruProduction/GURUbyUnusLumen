package com.unuslumen.app.data.brain

import com.unuslumen.app.data.memory.DvmSearch
import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QueryEngine — Four-tier retrieval with reciprocal rank fusion.
 *
 * Uses PolicyEngine to classify the query and activate only the relevant tiers.
 * Results from all active tiers are fused using reciprocal rank fusion (RRF).
 *
 * Tier 4 uses DVM (Dynamic Vector Memory) search instead of the dead TFLite
 * embedding model. DVM computes pseudo-vectors from text on-the-fly using
 * hash-based similarity. No model needed, no startup freeze, pure computation.
 */
class QueryEngine(
    private val memoryFactDao: MemoryFactDao,
    private val graphEngine: GraphEngine,
    private val signatureEngine: SignatureEngine = SignatureEngine,
    private val policyEngine: PolicyEngine = PolicyEngine(),
    private val prefetchEngine: PrefetchEngine? = null
) {

    companion object {
        const val WEIGHT_FTS = 1.0f
        const val WEIGHT_SIGNATURE = 0.7f
        const val WEIGHT_DVM = 1.0f
        const val WEIGHT_GRAPH = 0.2f
        const val WEIGHT_HOT_CACHE = 1.5f
        const val RRF_K = 60
    }

    suspend fun search(
        query: String,
        topK: Int = 5
    ): List<SearchResult> = withContext(Dispatchers.Default) {

        val policy = policyEngine.classifyQuery(query)
        val allFacts = memoryFactDao.getAllFacts()
        val rankedResults = mutableMapOf<String, Float>()
        val sourceTracking = mutableMapOf<String, MutableSet<String>>()

        // Tier 1: Hot cache
        if (PolicyEngine.TIER_HOT_CACHE in policy.tiers && prefetchEngine != null) {
            val cached = prefetchEngine.getCachedFacts()
            cached.forEachIndexed { rank, fact ->
                val score = WEIGHT_HOT_CACHE / (RRF_K + rank + 1)
                rankedResults[fact.id] = rankedResults.getOrDefault(fact.id, 0f) + score
                sourceTracking.getOrPut(fact.id) { mutableSetOf() }.add("CACHE")
            }
        }

        // Tier 2: Keyword search via LIKE.
        // The DAO method uses LIKE not FTS5 MATCH, so we pass individual terms
        // and merge results by counting how many terms each fact matched.
        // This ensures the keyword tier actually contributes relevant results
        // instead of searching for a literal FTS5 syntax string that matches nothing.
        if (PolicyEngine.TIER_FTS in policy.tiers) {
            val terms = query.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 1 }
                .take(5)

            // Also search the full raw query for exact phrase matches
            val allSearchResults = mutableListOf<MemoryFactEntity>()
            try {
                allSearchResults.addAll(memoryFactDao.searchFactsFts(query))
            } catch (e: Exception) { }

            // Search each individual term and collect results
            for (term in terms) {
                try {
                    allSearchResults.addAll(memoryFactDao.searchFactsFts(term))
                } catch (e: Exception) { }
            }

            // Rank by term match frequency: facts that match more terms rank higher
            val termMatchCount = mutableMapOf<String, Int>()
            for (fact in allSearchResults) {
                termMatchCount[fact.id] = termMatchCount.getOrDefault(fact.id, 0) + 1
            }
            val sortedFtsResults = allSearchResults
                .distinctBy { it.id }
                .sortedByDescending { termMatchCount[it.id] ?: 0 }

            sortedFtsResults.forEachIndexed { rank, fact ->
                val score = WEIGHT_FTS / (RRF_K + rank + 1)
                rankedResults[fact.id] = rankedResults.getOrDefault(fact.id, 0f) + score
                sourceTracking.getOrPut(fact.id) { mutableSetOf() }.add("FTS")
            }
        }

        // Tier 3: Signature search
        if (PolicyEngine.TIER_SIGNATURE in policy.tiers) {
            val sigResults = signatureEngine.search(query, allFacts, minSimilarity = 0.4f, topK = 10)
            sigResults.forEachIndexed { rank, (fact, _) ->
                val score = WEIGHT_SIGNATURE / (RRF_K + rank + 1)
                rankedResults[fact.id] = rankedResults.getOrDefault(fact.id, 0f) + score
                sourceTracking.getOrPut(fact.id) { mutableSetOf() }.add("SIG")
            }
        }

        // Tier 4: DVM search (replaces dead embedding tier)
        // Uses hash-based pseudo-vectors for semantic similarity without a model
        if (PolicyEngine.TIER_EMBEDDING in policy.tiers) {
            val dvmResults = DvmSearch.search(
                query = query,
                items = allFacts,
                vectorExtractor = { fact -> DvmSearch.textToPseudoVector(fact.fact) },
                minSimilarity = 0.25f,
                topK = 30
            )
            dvmResults.forEachIndexed { rank, (fact, _) ->
                val score = WEIGHT_DVM / (RRF_K + rank + 1)
                rankedResults[fact.id] = rankedResults.getOrDefault(fact.id, 0f) + score
                sourceTracking.getOrPut(fact.id) { mutableSetOf() }.add("DVM")
            }
        }

        // Graph traversal: boost facts connected to top results
        if (policy.enableGraph && rankedResults.isNotEmpty()) {
            val topFactIds = rankedResults.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }

            for (factId in topFactIds) {
                val connected = graphEngine.getConnectedFacts(factId)
                for ((connectedId, weight) in connected.take(2)) {
                    if (connectedId !in rankedResults) {
                        rankedResults[connectedId] = weight * WEIGHT_GRAPH / RRF_K
                        sourceTracking.getOrPut(connectedId) { mutableSetOf() }.add("GRAPH")
                    }
                }
            }
        }

        // Recency boost: facts saved more recently get a small score increase
        // so fresh memories surface before stale ones
        val now = System.currentTimeMillis()
        val recencyHalfLife = 24L * 60 * 60 * 1000 // 24 hours
        for ((factId, score) in rankedResults.toMap()) {
            val fact = allFacts.find { it.id == factId }
            if (fact != null) {
                val age = now - fact.extractedDate
                if (age > 0) {
                    val recencyBoost = kotlin.math.exp(-age.toFloat() / recencyHalfLife.toFloat()) * 0.15f
                    rankedResults[factId] = score + recencyBoost
                }
            }
        }

        // Build final results with actual source tracking
        val factMap = allFacts.associateBy { it.id }
        rankedResults.entries
            .sortedByDescending { it.value }
            .take(topK)
            .mapNotNull { (factId, score) ->
                factMap[factId]?.let { fact ->
                    SearchResult(
                        fact = fact,
                        score = score,
                        source = sourceTracking[factId]?.joinToString("+") ?: "UNKNOWN"
                    )
                }
            }
    }

    suspend fun getByCategory(category: String): List<MemoryFactEntity> = withContext(Dispatchers.Default) {
        memoryFactDao.getFactsByCategory(category)
    }

    suspend fun getByDomain(domain: String, topic: String? = null): List<MemoryFactEntity> = withContext(Dispatchers.Default) {
        if (topic != null) {
            memoryFactDao.getFactsByDomainTopic(domain, topic)
        } else {
            memoryFactDao.getFactsByDomain(domain)
        }
    }

}

data class SearchResult(
    val fact: MemoryFactEntity,
    val score: Float,
    val source: String
)
