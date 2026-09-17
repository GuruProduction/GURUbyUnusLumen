package com.unuslumen.app.data.memory

import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.dao.MessageDao
import com.unuslumen.app.database.entity.MemoryFactEntity
import com.unuslumen.app.database.entity.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

/**
 * DVM-powered search engine using Hamming distance on binary pseudo-vectors.
 * No embeddings API needed - pure hash-based similarity.
 *
 * Uses FTS for pre-filtering then DvmSearch for ranking.
 * 80% containment + 20% Jaccard handles query/memory length asymmetry.
 */
@Factory
class VectorSearchEngine(
    private val messageDao: MessageDao,
    private val memoryFactDao: MemoryFactDao
) {

    data class ScoredMessage(
        val message: MessageEntity,
        val score: Float
    )

    data class ScoredFact(
        val fact: MemoryFactEntity,
        val score: Float
    )

    /**
     * DVM search - no embeddings needed, computes pseudo-vectors from text on-the-fly.
     * Fast, deterministic, no API calls.
     */
    suspend fun searchMessages(
        query: String,
        queryEmbedding: List<Float> = emptyList(), // Ignored - DVM doesn't need embeddings
        topK: Int = 20,
        minScore: Float = 0.25f // DVM threshold is lower than cosine
    ): List<ScoredMessage> = withContext(Dispatchers.Default) {
        val ftsCandidates = if (query.length > 3) {
            try {
                messageDao.searchMessagesFts(query)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }.take(500)

        val sourceList = if (ftsCandidates.isEmpty()) {
            messageDao.getAllMessages().takeLast(500)
        } else {
            ftsCandidates
        }

        // DVM: compute pseudo-vector from query text, compare with all candidates
        val queryVector = DvmSearch.textToPseudoVector(query)

        sourceList
            .map { message ->
                // Compute pseudo-vector from message content on-the-fly
                val targetVector = DvmSearch.textToPseudoVector(message.content)
                val score = DvmSearch.combinedSimilarity(queryVector, targetVector)
                ScoredMessage(message, score)
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    suspend fun searchFacts(
        query: String,
        queryEmbedding: List<Float> = emptyList(), // Ignored - DVM doesn't need embeddings
        topK: Int = 10,
        minScore: Float = 0.25f // DVM threshold is lower than cosine
    ): List<ScoredFact> = withContext(Dispatchers.Default) {
        val ftsCandidates = if (query.length > 3) {
            try {
                memoryFactDao.searchFactsFts(query)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val sourceList = if (ftsCandidates.isEmpty()) {
            memoryFactDao.getAllFacts()
        } else {
            ftsCandidates
        }

        // DVM: compute pseudo-vector from query text, compare with all candidates
        val queryVector = DvmSearch.textToPseudoVector(query)

        sourceList
            .map { fact ->
                // Compute pseudo-vector from fact content on-the-fly
                val targetVector = DvmSearch.textToPseudoVector(fact.fact)
                val score = DvmSearch.combinedSimilarity(queryVector, targetVector)
                ScoredFact(fact, score)
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Legacy cosine similarity - kept for backwards compatibility with stored embeddings.
     * DVM search ignores this and uses textToPseudoVector instead.
     */
    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }

    fun cosineSimilarity(a: List<Float>, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }
}
