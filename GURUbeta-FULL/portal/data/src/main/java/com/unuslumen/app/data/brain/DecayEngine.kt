package com.unuslumen.app.data.brain

import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max

/**
 * DecayEngine — Exponential decay on memory strength with layer promotion.
 *
 * Strength decays over time. Accessing a memory boosts its strength.
 * Memories that survive long enough in BUFFER get promoted to EPISODIC.
 * Strong EPISODIC memories get promoted to SEMANTIC (consolidated knowledge).
 *
 * This mirrors how biological memory works:
 * - BUFFER = short-term (hippocampus, easily lost)
 * - EPISODIC = medium-term (event memories, context-dependent)
 * - SEMANTIC = long-term (consolidated facts, context-free)
 *
 * Ported from Cerebrum's decay crate to Kotlin.
 */
class DecayEngine(
    private val memoryFactDao: MemoryFactDao
) {

    companion object {
        // Decay rate: strength halves every ~7 days
        // decay = exp(-elapsed / halfLife)
        const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days in milliseconds

        // Layer promotion thresholds
        const val PROMOTION_BUFFER_TO_EPISODIC_ACCESS_COUNT = 3
        const val PROMOTION_EPISODIC_TO_SEMANTIC_ACCESS_COUNT = 10
        const val PROMOTION_EPISODIC_TO_SEMANTIC_MIN_STRENGTH = 0.7f

        // Minimum strength before pruning
        const val PRUNE_THRESHOLD = 0.05f

        // Boost from being recalled
        const val RECALL_BOOST = 0.3f
        const val MAX_STRENGTH = 1.0f
    }

    /**
     * Apply decay to all facts. Should be called periodically (e.g., once per day).
     * Returns the number of facts pruned.
     */
    suspend fun applyDecayAll(currentTime: Long): Int = withContext(Dispatchers.Default) {
        val candidates = memoryFactDao.getDecayCandidates(200)
        var pruned = 0

        for (fact in candidates) {
            val newStrength = calculateDecayedStrength(fact, currentTime)

            if (newStrength < PRUNE_THRESHOLD && fact.layer == "BUFFER") {
                // Prune weak BUFFER facts
                memoryFactDao.deleteFact(fact.id)
                pruned++
            } else if (newStrength != fact.strength) {
                memoryFactDao.updateDecay(fact.id, newStrength, fact.accessCount)
            }
        }

        pruned
    }

    /**
     * Calculate the decayed strength for a fact based on time since last access.
     */
    fun calculateDecayedStrength(fact: MemoryFactEntity, currentTime: Long): Float {
        val lastAccess = if (fact.lastRecalledDate > 0) fact.lastRecalledDate else fact.extractedDate
        val elapsed = currentTime - lastAccess

        if (elapsed <= 0) return fact.strength

        // Exponential decay: strength * exp(-elapsed / halfLife)
        val decayFactor = exp(-elapsed.toFloat() / HALF_LIFE_MS.toFloat())
        return max(0f, fact.strength * decayFactor)
    }

    /**
     * Boost a fact's strength when it's recalled. Also increments access count.
     * Then checks if it should be promoted to a higher layer.
     */
    suspend fun onRecall(factId: String, currentTime: Long) = withContext(Dispatchers.Default) {
        val fact = memoryFactDao.getFact(factId) ?: return@withContext
        val newStrength = minOf(MAX_STRENGTH, fact.strength + RECALL_BOOST)
        val newAccessCount = fact.accessCount + 1
        memoryFactDao.updateDecay(factId, newStrength, newAccessCount)
        memoryFactDao.updateRecalledDate(factId, currentTime)

        // Check for layer promotion
        val newLayer = checkPromotion(fact.copy(strength = newStrength, accessCount = newAccessCount))
        if (newLayer != fact.layer) {
            memoryFactDao.promoteLayer(factId, newLayer)
        }
    }

    /**
     * Check if a fact should be promoted to a higher layer.
     */
    fun checkPromotion(fact: MemoryFactEntity): String {
        return when (fact.layer) {
            "BUFFER" -> {
                if (fact.accessCount >= PROMOTION_BUFFER_TO_EPISODIC_ACCESS_COUNT) {
                    "EPISODIC"
                } else {
                    "BUFFER"
                }
            }
            "EPISODIC" -> {
                if (fact.accessCount >= PROMOTION_EPISODIC_TO_SEMANTIC_ACCESS_COUNT &&
                    fact.strength >= PROMOTION_EPISODIC_TO_SEMANTIC_MIN_STRENGTH
                ) {
                    "SEMANTIC"
                } else {
                    "EPISODIC"
                }
            }
            "SEMANTIC" -> "SEMANTIC" // Already at the top
            else -> "BUFFER"
        }
    }
}
