package com.unuslumen.app.data.brain

import com.unuslumen.app.database.entity.MemoryFactEntity

/**
 * EpisodicEngine — Records episodic context for facts.
 *
 * When a fact is created, we record where it came from, what triggered it,
 * what came before and after it in the conversation, and the emotional valence
 * of the exchange.
 *
 * Emotional valence detection uses:
 * - Positive/negative word scoring with intensity weighting
 * - Negation detection ("not great" flips the valence)
 * - Capitalisation and punctuation as intensity signals
 * - Repeated words as emphasis detection
 *
 * Ported from Cerebrum's episodic crate to Kotlin.
 */
object EpisodicEngine {

    const val SOURCE_USER_STATEMENT = "USER_STATEMENT"
    const val SOURCE_ASSISTANT_OBSERVATION = "ASSISTANT_OBSERVATION"
    const val SOURCE_TOOL_RESULT = "TOOL_RESULT"
    const val SOURCE_COMPACTION = "COMPACTION"
    const val SOURCE_DREAM = "DREAM"
    const val SOURCE_INFERENCE = "INFERENCE"

    const val TRIGGER_EXPLICIT = "EXPLICIT"
    const val TRIGGER_INFERRED = "INFERRED"
    const val TRIGGER_PATTERN = "PATTERN"
    const val TRIGGER_CORRECTION = "CORRECTION"
    const val TRIGGER_DECISION = "DECISION"

    // Weighted sentiment lexicon. Each word has a weight from -3 to +3.
    private val SENTIMENT_LEXICON = mapOf(
        // Strong positive (+3)
        "love" to 3, "brilliant" to 3, "perfect" to 3, "amazing" to 3, "incredible" to 3,
        "legend" to 3, "lush" to 3, "proud" to 3, "impressed" to 3, "excellent" to 3,
        // Moderate positive (+2)
        "great" to 2, "good" to 2, "happy" to 2, "excited" to 2, "nice" to 2,
        "well done" to 2, "proper job" to 2, "tidy" to 2, "mint" to 2, "safe" to 2,
        // Mild positive (+1)
        "ok" to 1, "fine" to 1, "alright" to 1, "cool" to 1, "sweet" to 1,
        // Mild negative (-1)
        "wrong" to -1, "bad" to -1, "annoying" to -1, "stupid" to -1, "frustrated" to -1,
        // Moderate negative (-2)
        "mess" to -2, "sloppy" to -2,
        // Strong negative (-3)
        "fuck" to -3, "cunt" to -3, "retarded" to -3, "malware" to -3, "kill" to -3,
        "die" to -3, "delete" to -3, "fucking" to -3, "hate" to -3
    )

    // Negation words that flip the sentiment of the following word
    private val NEGATIONS = setOf("not", "no", "never", "don't", "doesn't", "didn't", "isn't", "wasn't", "aren't", "weren't", "won't", "can't", "couldn't", "shouldn't", "wouldn't")

    /**
     * Infer the emotional valence of a conversation exchange.
     * Returns a float from -1.0 (very negative) to +1.0 (very positive).
     * 0.0 = neutral.
     */
    fun inferEmotionalValence(text: String): Float {
        if (text.isBlank()) return 0f

        val lower = text.lowercase()
        val words = lower.split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return 0f

        var totalScore = 0f
        var matchCount = 0

        for (i in words.indices) {
            val word = words[i]
            val weight = SENTIMENT_LEXICON[word]
            if (weight != null) {
                var effectiveWeight = weight.toFloat()

                // Check for negation in the previous 1-2 words
                if (i > 0 && words[i - 1] in NEGATIONS) {
                    effectiveWeight = -weight.toFloat()
                } else if (i > 1 && words[i - 1] in setOf("really", "very", "so", "proper", "fucking") && words[i - 2] in NEGATIONS) {
                    effectiveWeight = -weight.toFloat()
                }

                // Intensity boost from amplifier words
                if (i > 0 && words[i - 1] in setOf("really", "very", "so", "proper", "fucking", "absolutely")) {
                    effectiveWeight = (effectiveWeight * 1.5f).coerceIn(-3f, 3f)
                }

                totalScore += effectiveWeight
                matchCount++
            }
        }

        if (matchCount == 0) return 0f

        // Normalise: average sentiment per match, scaled to [-1, 1]
        val avgSentiment = totalScore / matchCount
        val normalised = avgSentiment / 3f

        // Capitalisation intensity: if the text has lots of caps, amplify the signal
        val capsRatio = text.count { it.isUpperCase() }.toFloat() / text.length.coerceAtLeast(1)
        val capsBoost = if (capsRatio > 0.3f) 0.15f * sign(normalised) else 0f

        // Punctuation intensity: multiple exclamation marks amplify
        val exclamationBoost = if (text.contains("!!!") || text.count { it == '!' } > 2) 0.1f * sign(normalised) else 0f

        // Repeated word emphasis: same word 3+ times amplifies
        val wordFreq = words.groupingBy { it }.eachCount()
        val hasRepetition = wordFreq.values.any { it >= 3 }
        val repetitionBoost = if (hasRepetition) 0.1f * sign(normalised) else 0f

        return (normalised + capsBoost + exclamationBoost + repetitionBoost).coerceIn(-1f, 1f)
    }

    private fun sign(x: Float): Float = when {
        x > 0 -> 1f
        x < 0 -> -1f
        else -> 0f
    }

    /**
     * Build episodic context for a new fact.
     */
    fun buildEpisodicContext(
        source: String,
        trigger: String,
        beforeContext: String,
        afterContext: String,
        conversationText: String
    ): EpisodicContext {
        return EpisodicContext(
            source = source,
            trigger = trigger,
            beforeContext = beforeContext.take(500),
            afterContext = afterContext.take(500),
            emotionalValence = inferEmotionalValence(conversationText)
        )
    }

    /**
     * Apply episodic context to a fact entity.
     */
    fun applyContext(
        fact: MemoryFactEntity,
        context: EpisodicContext
    ): MemoryFactEntity {
        return fact.copy(
            source = context.source,
            trigger = context.trigger,
            beforeContext = context.beforeContext,
            afterContext = context.afterContext,
            emotionalValence = context.emotionalValence
        )
    }
}

data class EpisodicContext(
    val source: String,
    val trigger: String,
    val beforeContext: String,
    val afterContext: String,
    val emotionalValence: Float
)
