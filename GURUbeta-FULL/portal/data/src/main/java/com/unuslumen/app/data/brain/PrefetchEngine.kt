package com.unuslumen.app.data.brain

import android.content.Context
import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PrefetchEngine — Predicts what facts will be needed next and preloads them.
 *
 * Does NOT depend on QueryEngine. BrainService performs the search and passes
 * results to prefetchEngine.cacheSearchResults(), which avoids the circular
 * dependency that would exist if PrefetchEngine took QueryEngine as a param.
 *
 * Cache persists to SharedPreferences so it survives app restarts.
 */
class PrefetchEngine(
    private val graphEngine: GraphEngine,
    private val memoryFactDao: MemoryFactDao,
    private val context: Context
) {

    private val prefs = context.getSharedPreferences("guru_prefetch_cache", Context.MODE_PRIVATE)
    private val hotCache = LinkedHashMap<String, CacheEntry>()
    private val maxCacheSize = 50

    data class CacheEntry(
        val fact: MemoryFactEntity,
        var lastAccess: Long,
        val prefetchReason: String
    )

    /**
     * Cache search results from BrainService. Also prefetches graph-connected facts.
     */
    suspend fun cacheSearchResults(
        results: List<SearchResult>,
        currentTime: Long
    ) = withContext(Dispatchers.Default) {
        for (result in results.take(5)) {
            addToCache(result.fact, currentTime, "search result")

            val connected = graphEngine.getConnectedFacts(result.fact.id)
            for ((connectedId, _) in connected.take(3)) {
                val connectedFact = memoryFactDao.getFact(connectedId)
                if (connectedFact != null) {
                    addToCache(connectedFact, currentTime, "graph from: ${result.fact.id}")
                }
            }
        }
        evictOldEntries(currentTime)
        persistCacheIds()
    }

    fun getFromCache(factId: String): MemoryFactEntity? {
        val entry = hotCache[factId] ?: return null
        entry.lastAccess = System.currentTimeMillis()
        return entry.fact
    }

    fun addToCache(fact: MemoryFactEntity, currentTime: Long, reason: String = "accessed") {
        hotCache[fact.id] = CacheEntry(fact, currentTime, reason)
        evictOldEntries(currentTime)
        persistCacheIds()
    }

    fun getCachedFacts(): List<MemoryFactEntity> = hotCache.values.map { it.fact }

    suspend fun seedCache(currentTime: Long) = withContext(Dispatchers.Default) {
        val savedIds = prefs.getStringSet("cached_fact_ids", emptySet()) ?: emptySet()
        val allFacts = memoryFactDao.getAllFacts()
        val factMap = allFacts.associateBy { it.id }

        for (factId in savedIds) {
            factMap[factId]?.let { addToCache(it, currentTime, "restored from persistence") }
        }

        if (hotCache.size < maxCacheSize / 2) {
            val topFacts = allFacts
                .sortedWith(compareByDescending<MemoryFactEntity> { it.strength }.thenByDescending { it.extractedDate })
                .take(maxCacheSize / 2)
            for (fact in topFacts) {
                addToCache(fact, currentTime, "seeded: high strength")
            }
        }
    }

    fun clearCache() {
        hotCache.clear()
        prefs.edit().remove("cached_fact_ids").apply()
    }

    fun clearFromCache(factId: String) {
        hotCache.remove(factId)
        persistCacheIds()
    }

    private fun evictOldEntries(currentTime: Long) {
        if (hotCache.size <= maxCacheSize) return
        val toEvict = hotCache.entries
            .sortedBy { it.value.lastAccess }
            .take(hotCache.size - maxCacheSize)
        for (entry in toEvict) {
            hotCache.remove(entry.key)
        }
    }

    private fun persistCacheIds() {
        prefs.edit().putStringSet("cached_fact_ids", hotCache.keys).apply()
    }
}
