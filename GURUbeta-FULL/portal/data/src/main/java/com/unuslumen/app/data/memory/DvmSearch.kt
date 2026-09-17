package com.unuslumen.app.data.memory

/**
 * DVM (Dynamic Vector Memory) - Fast in-memory search using Hamming distance
 * on binary pseudo-vectors. No embeddings API needed - pure hash-based.
 *
 * Approach:
 * - 4096 dimensions (fixed-size binary vector)
 * - 2 hash positions per word unigram
 * - 2 hash positions per word bigram
 * - Stop words filtered before hashing
 * - Combined similarity: 80% containment + 20% Jaccard
 *
 * Containment measures what fraction of query bits are in the target.
 * This handles the density asymmetry where short queries have few bits
 * and long memories have many.
 */
object DvmSearch {

    private const val VECTOR_SIZE = 4096

    // Common English stop words - filtered before hashing
    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
        "be", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "must", "shall", "can", "need",
        "dare", "ought", "used", "it", "its", "this", "that", "these", "those",
        "i", "you", "he", "she", "we", "they", "what", "which", "who", "whom",
        "when", "where", "why", "how", "all", "each", "every", "both", "few",
        "more", "most", "other", "some", "such", "no", "nor", "not", "only",
        "own", "same", "so", "than", "too", "very", "just", "also", "now",
        "here", "there", "then", "once", "if", "about", "into", "through",
        "during", "before", "after", "above", "below", "between", "under",
        "again", "further", "any", "being"
    )

    /**
     * Convert text to a binary pseudo-vector.
     * Uses DJB2 hash to map words/bigrams to bit positions.
     * Each word contributes 2 bits, each bigram contributes 2 bits.
     */
    fun textToPseudoVector(text: String): ByteArray {
        val vector = ByteArray(VECTOR_SIZE / 8) { 0 }

        // Tokenise: lowercase, split on non-alphanumeric, filter stop words
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(" ")
            .filter { it.isNotBlank() && it !in STOP_WORDS && it.length > 1 }

        // Hash each word (2 positions per word)
        for (word in words) {
            val baseHash = djb2Hash(word)
            setBit(vector, baseHash % VECTOR_SIZE)
            setBit(vector, (baseHash * 31) % VECTOR_SIZE)
        }

        // Hash each bigram (2 positions per bigram)
        for (i in 0 until words.size - 1) {
            val bigram = words[i] + " " + words[i + 1]
            val baseHash = djb2Hash(bigram)
            setBit(vector, baseHash % VECTOR_SIZE)
            setBit(vector, (baseHash * 31) % VECTOR_SIZE)
        }

        return vector
    }

    /**
     * DJB2 hash - simple, fast, good distribution.
     */
    private fun djb2Hash(str: String): Int {
        var hash = 5381
        for (char in str) {
            hash = ((hash shl 5) + hash) + char.code
        }
        return hash.absoluteValue
    }

    /**
     * Set a bit in the byte array at the given position.
     */
    private fun setBit(vector: ByteArray, position: Int) {
        val byteIndex = position / 8
        val bitIndex = position % 8
        if (byteIndex in vector.indices) {
            vector[byteIndex] = (vector[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }
    }

    /**
     * Check if a bit is set at the given position.
     */
    private fun isBitSet(vector: ByteArray, position: Int): Boolean {
        val byteIndex = position / 8
        val bitIndex = position % 8
        return if (byteIndex in vector.indices) {
            (vector[byteIndex].toInt() and (1 shl bitIndex)) != 0
        } else false
    }

    /**
     * Combined similarity: 80% containment + 20% Jaccard.
     *
     * Containment: what fraction of query bits are contained in target.
     * Handles the case where queries are short (few bits) and memories
     * are long (many bits).
     *
     * Jaccard: symmetric overlap measure for balance.
     */
    fun combinedSimilarity(queryVector: ByteArray, targetVector: ByteArray): Float {
        var intersection = 0
        var union = 0
        var queryBits = 0

        for (i in 0 until VECTOR_SIZE) {
            val queryBit = isBitSet(queryVector, i)
            val targetBit = isBitSet(targetVector, i)

            if (queryBit || targetBit) {
                union++
                if (queryBit && targetBit) {
                    intersection++
                }
            }
            if (queryBit) {
                queryBits++
            }
        }

        val containment = if (queryBits == 0) 0f else intersection.toFloat() / queryBits
        val jaccard = if (union == 0) 0f else intersection.toFloat() / union

        return 0.8f * containment + 0.2f * jaccard
    }

    /**
     * Hamming distance - count of positions where bits differ.
     */
    fun hammingDistance(a: ByteArray, b: ByteArray): Int {
        var distance = 0
        for (i in a.indices) {
            val xor = a[i].toInt() xor b[i].toInt()
            distance += Integer.bitCount(xor and 0xFF)
        }
        return distance
    }

    /**
     * Search for most similar vectors using combined similarity.
     * Returns results sorted by descending similarity.
     */
    fun <T> search(
        query: String,
        items: List<T>,
        vectorExtractor: (T) -> ByteArray,
        minSimilarity: Float = 0.3f,
        topK: Int = 20
    ): List<Pair<T, Float>> {
        val queryVector = textToPseudoVector(query)

        return items
            .map { item ->
                val targetVector = vectorExtractor(item)
                val score = combinedSimilarity(queryVector, targetVector)
                item to score
            }
            .filter { it.second >= minSimilarity }
            .sortedByDescending { it.second }
            .take(topK)
    }
}

// Extension for absolute value on Int
private val Int.absoluteValue: Int get() = if (this < 0) -this else this