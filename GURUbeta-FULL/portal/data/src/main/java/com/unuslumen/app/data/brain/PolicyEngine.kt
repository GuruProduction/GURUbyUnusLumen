package com.unuslumen.app.data.brain

/**
 * PolicyEngine — Retrieval policy and routing rules.
 *
 * Classifies queries and activates the appropriate retrieval tiers.
 * Uses hybrid classification so queries that span multiple categories
 * activate multiple tiers instead of being locked to one.
 *
 * Ported from Cerebrum's policy crate to Kotlin.
 */
class PolicyEngine {

    companion object {
        const val QUERY_SPECIFIC = "SPECIFIC"
        const val QUERY_CONCEPTUAL = "CONCEPTUAL"
        const val QUERY_ENTITY = "ENTITY"
        const val QUERY_RECENT = "RECENT"
        const val QUERY_GENERAL = "GENERAL"

        const val TIER_HOT_CACHE = "HOT_CACHE"
        const val TIER_FTS = "FTS"
        const val TIER_SIGNATURE = "SIGNATURE"
        const val TIER_EMBEDDING = "EMBEDDING"
        const val TIER_GRAPH = "GRAPH"
    }

    private val entityPatterns = listOf("steven", "sheldon", "lux", "nexus", "guru", "mac", "bristol")
    private val recentPatterns = listOf("today", "yesterday", "recent", "last", "just now", "this week")
    private val technicalPatterns = listOf("database", "schema", "migration", "context", "compaction", "memory", "brain", "embedding", "signature", "decay", "graph", "engine", "code", "build", "fix", "bug")
    private val conceptualPatterns = listOf("why", "how", "what if", "explain", "design", "architecture", "strategy", "plan", "approach", "should we")

    fun classifyQuery(query: String): QueryClassification {
        val lower = query.lowercase().trim()
        val wordCount = lower.split(Regex("\\s+")).filter { it.isNotBlank() }.size

        val entityMatch = entityPatterns.any { lower.contains(it) }
        val recentMatch = recentPatterns.any { lower.contains(it) }
        val specificMatch = wordCount <= 4 && lower.length < 50
        val conceptualMatch = wordCount > 6 || lower.length > 80
        val technicalMatch = technicalPatterns.any { lower.contains(it) }

        // Primary query type for logging
        val queryType = when {
            recentMatch -> QUERY_RECENT
            entityMatch -> QUERY_ENTITY
            conceptualMatch -> QUERY_CONCEPTUAL
            specificMatch -> QUERY_SPECIFIC
            else -> QUERY_GENERAL
        }

        // Hybrid tier activation: start with base tiers for primary type,
        // then add tiers from secondary matches
        val tiers = mutableSetOf<String>()

        // Base tiers for primary type
        when (queryType) {
            QUERY_SPECIFIC -> {
                tiers.add(TIER_FTS)
                tiers.add(TIER_SIGNATURE)
            }
            QUERY_CONCEPTUAL -> {
                tiers.add(TIER_EMBEDDING)
                tiers.add(TIER_GRAPH)
            }
            QUERY_ENTITY -> {
                tiers.add(TIER_GRAPH)
                tiers.add(TIER_FTS)
            }
            QUERY_RECENT -> {
                tiers.add(TIER_HOT_CACHE)
                tiers.add(TIER_FTS)
            }
            else -> {
                tiers.add(TIER_FTS)
                tiers.add(TIER_SIGNATURE)
            }
        }

        // Add embedding tier for technical queries even if classified as specific
        // e.g. "fix database migration" is short but needs semantic understanding
        if (technicalMatch && TIER_EMBEDDING !in tiers) {
            tiers.add(TIER_EMBEDDING)
        }

        // Add FTS for conceptual queries so specific terms still match
        if (conceptualMatch && TIER_FTS !in tiers) {
            tiers.add(TIER_FTS)
        }

        // Add signature as a cheap first-pass filter for any query that has FTS
        if (TIER_FTS in tiers && TIER_SIGNATURE !in tiers) {
            tiers.add(TIER_SIGNATURE)
        }

        val enableGraph = queryType in setOf(QUERY_ENTITY, QUERY_CONCEPTUAL, QUERY_GENERAL) || technicalMatch

        return QueryClassification(
            queryType = queryType,
            tiers = tiers,
            enableGraph = enableGraph
        )
    }

    data class QueryClassification(
        val queryType: String,
        val tiers: Set<String>,
        val enableGraph: Boolean
    )
}
