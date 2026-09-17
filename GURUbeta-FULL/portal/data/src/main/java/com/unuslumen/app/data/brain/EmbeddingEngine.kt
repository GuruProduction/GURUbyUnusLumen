package com.unuslumen.app.data.brain

import com.unuslumen.app.data.memory.LocalEmbeddingService
import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * EmbeddingEngine — Generates, stores, and searches dense vector embeddings.
 *
 * Uses the existing LocalEmbeddingService (EmbeddingGemma TFLite model) for
 * generating 768-dim embeddings, then projects down to 256-dim using random
 * projection (Johnson-Lindenstrauss) for compact storage and fast cosine similarity.
 *
 * This is the second-tier similarity filter. More precise than signatures,
 * slower to compute but cached in the database as a List<Float>.
 *
 * Ported from Cerebrum's embedding crate to Kotlin.
 */
class EmbeddingEngine(
    private val embeddingService: LocalEmbeddingService
) {

    companion object {
        const val FULL_DIMS = 768
        const val PROJECTED_DIMS = 256

        // Random projection matrix — seeded for determinism.
        // Each entry is a random Gaussian divided by sqrt(FULL_DIMS) to
        // preserve distances per Johnson-Lindenstrauss lemma.
        private val PROJECTION_MATRIX: Array<FloatArray> by lazy {
            val random = java.util.Random(42)
            Array(PROJECTED_DIMS) {
                FloatArray(FULL_DIMS) {
                    val u1 = maxOf(random.nextDouble().toFloat(), 1e-38f)
                    val u2 = random.nextDouble().toFloat()
                    val r = sqrt(-2f * ln(u1))
                    val gaussian = r * cos(2f * Math.PI.toFloat() * u2)
                    gaussian / sqrt(FULL_DIMS.toFloat())
                }
            }
        }
    }

    /**
     * Generate a 768-dim embedding using the TFLite model, then project to 256-dim.
     * Returns the projected embedding as a List<Float> for storage in the database.
     */
    suspend fun generateEmbedding(text: String): List<Float> = withContext(Dispatchers.Default) {
        val result = embeddingService.generateEmbedding(text)
        if (result.isFailure) {
            Log.e("guru_brain", "Embedding generation failed: ${result.exceptionOrNull()?.message}")
            return@withContext emptyList()
        }

        val fullEmbedding = result.getOrNull() ?: return@withContext emptyList()
        if (fullEmbedding.isEmpty()) {
            Log.e("guru_brain", "Embedding generation returned empty list")
            return@withContext emptyList()
        }

        projectEmbedding(fullEmbedding).toList()
    }

    /**
     * Project a 768-dim embedding down to 256-dim using random projection.
     * Johnson-Lindenstrauss lemma guarantees distances are approximately preserved.
     */
    fun projectEmbedding(fullEmbedding: List<Float>): FloatArray {
        if (fullEmbedding.isEmpty()) return FloatArray(0)
        val projected = FloatArray(PROJECTED_DIMS)
        for (i in 0 until PROJECTED_DIMS) {
            var sum = 0f
            for (j in fullEmbedding.indices) {
                sum += PROJECTION_MATRIX[i][j] * fullEmbedding[j]
            }
            projected[i] = sum
        }
        return projected
    }

    /**
     * Cosine similarity between two dense vectors.
     * Returns 0.0 to 1.0 for non-negative embeddings, -1.0 to 1.0 in general.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            throw IllegalArgumentException("Embedding dimension mismatch: ${a.size} vs ${b.size}. Ensure all embeddings are projected to PROJECTED_DIMS.")
        }
        if (a.isEmpty()) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }

    /**
     * Search for most similar facts using cosine similarity on stored embeddings.
     * Facts with no stored embedding are skipped — they should be backfilled
     * by DreamEngine, not generated on-the-fly during search (too slow).
     */
    suspend fun search(
        query: String,
        facts: List<MemoryFactEntity>,
        minSimilarity: Float = 0.5f,
        topK: Int = 20
    ): List<Pair<MemoryFactEntity, Float>> = withContext(Dispatchers.Default) {

        val queryEmbeddingResult = embeddingService.generateEmbedding(query)
        if (queryEmbeddingResult.isFailure) return@withContext emptyList()

        val queryFull = queryEmbeddingResult.getOrNull() ?: return@withContext emptyList()
        if (queryFull.isEmpty()) return@withContext emptyList()

        val queryProjected = projectEmbedding(queryFull)

        facts.map { fact ->
            val factEmbedding = if (fact.embedding.isNotEmpty()) {
                fact.embedding.toFloatArray()
            } else {
                FloatArray(0)
            }

            val score = if (factEmbedding.isEmpty()) 0f else try { cosineSimilarity(queryProjected, factEmbedding) } catch (e: IllegalArgumentException) { 0f }
            fact to score
        }
            .filter { it.second >= minSimilarity }
            .sortedByDescending { it.second }
            .take(topK)
    }

    private fun List<Float>.toFloatArray(): FloatArray {
        return FloatArray(this.size) { this[it] }
    }
}
