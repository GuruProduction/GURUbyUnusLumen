package com.unuslumen.app.data.brain

import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlin.math.absoluteValue
import kotlin.math.min

/**
 * SignatureEngine — Random indexing for 256-bit binary signatures.
 *
 * Generates compact binary signatures from text using random indexing.
 * Each word maps to a fixed set of bit positions in a 256-bit vector.
 * Signatures are compared using Hamming distance — fast, cheap, deterministic.
 *
 * This is the first-tier similarity filter. It's not as precise as embeddings
 * but it's instantaneous and runs on every fact in the database.
 *
 * Ported from Cerebrum's signature crate to Kotlin.
 */
object SignatureEngine {

    const val SIGNATURE_BITS = 256
    const val SIGNATURE_BYTES = 32 // 256 / 8
    const val BITS_PER_WORD = 3
    const val BITS_PER_BIGRAM = 2

    // Seeded random projection vectors — deterministic across runs

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
        "be", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "must", "shall", "can", "need",
        "it", "its", "this", "that", "these", "those", "i", "you", "he", "she",
        "we", "they", "what", "which", "who", "whom", "when", "where", "why",
        "how", "all", "each", "every", "both", "few", "more", "most", "other",
        "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than",
        "too", "very", "just", "also", "now", "here", "there", "then", "once",
        "if", "about", "into", "through", "during", "before", "after", "above",
        "below", "between", "under", "again", "further", "any", "being"
    )

    /**
     * Generate a 256-bit binary signature from text.
     * Returns as a Base64-encoded string for storage in the database.
     */
    fun generateSignature(text: String): String {
        val vector = ByteArray(SIGNATURE_BYTES) { 0 }

        val words = text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(" ")
            .filter { it.isNotBlank() && it !in STOP_WORDS && it.length > 1 }

        // Hash each word to BITS_PER_WORD positions
        for (word in words) {
            val hash = stableHash(word)
            for (i in 0 until BITS_PER_WORD) {
                val position = ((hash * 31 + i * 2654435761.toInt()) and 0x7FFFFFFF) % SIGNATURE_BITS
                setBit(vector, position)
            }
        }

        // Hash each bigram to BITS_PER_BIGRAM positions
        for (i in 0 until words.size - 1) {
            val bigram = words[i] + "_" + words[i + 1]
            val hash = stableHash(bigram)
            for (j in 0 until BITS_PER_BIGRAM) {
                val position = ((hash * 31 + j * 2654435761.toInt()) and 0x7FFFFFFF) % SIGNATURE_BITS
                setBit(vector, position)
            }
        }

        return android.util.Base64.encodeToString(vector, android.util.Base64.NO_WRAP)
    }

    /**
     * Decode a Base64-encoded signature back to a byte array.
     */
    fun decodeSignature(encoded: String): ByteArray {
        return if (encoded.isBlank()) {
            ByteArray(SIGNATURE_BYTES)
        } else {
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * Hamming distance between two signatures.
     * Lower = more similar. 0 = identical. 256 = completely different.
     */
    fun hammingDistance(a: ByteArray, b: ByteArray): Int {
        val minLen = min(a.size, b.size)
        var distance = 0
        for (i in 0 until minLen) {
            val xor = a[i].toInt() xor b[i].toInt()
            distance += Integer.bitCount(xor and 0xFF)
        }
        // Count remaining bits in the longer vector as differences
        if (a.size > b.size) {
            for (i in b.size until a.size) {
                distance += Integer.bitCount(a[i].toInt() and 0xFF)
            }
        } else if (b.size > a.size) {
            for (i in a.size until b.size) {
                distance += Integer.bitCount(b[i].toInt() and 0xFF)
            }
        }
        return distance
    }

    /**
     * Similarity score from 0.0 to 1.0 based on Hamming distance.
     * 1.0 = identical, 0.0 = completely different.
     */
    fun similarity(a: ByteArray, b: ByteArray): Float {
        val distance = hammingDistance(a, b)
        return 1.0f - (distance.toFloat() / SIGNATURE_BITS)
    }

    /**
     * Similarity between a query string and a stored signature.
     */
    fun similarityToText(query: String, storedSignature: String): Float {
        val querySig = generateSignature(query)
        val queryBytes = decodeSignature(querySig)
        val storedBytes = decodeSignature(storedSignature)
        return similarity(queryBytes, storedBytes)
    }

    /**
     * Find the most similar facts to a query using signature comparison.
     * Fast first-pass filter. Results should be refined by EmbeddingEngine.
     */
    fun search(
        query: String,
        facts: List<MemoryFactEntity>,
        minSimilarity: Float = 0.35f,
        topK: Int = 50
    ): List<Pair<MemoryFactEntity, Float>> {
        val querySig = generateSignature(query)
        val queryBytes = decodeSignature(querySig)

        return facts
            .map { fact ->
                val factBytes = if (fact.signature.isNotBlank()) {
                    decodeSignature(fact.signature)
                } else {
                    decodeSignature(generateSignature(fact.fact))
                }
                fact to similarity(queryBytes, factBytes)
            }
            .filter { it.second >= minSimilarity }
            .sortedByDescending { it.second }
            .take(topK)
    }

    private fun stableHash(str: String): Int {
        var hash = 5381
        for (char in str) {
            hash = ((hash shl 5) + hash) + char.code
        }
        return hash.absoluteValue
    }

    private fun setBit(vector: ByteArray, position: Int) {
        val byteIndex = position / 8
        val bitIndex = position % 8
        if (byteIndex in vector.indices) {
            vector[byteIndex] = (vector[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }
    }
}
