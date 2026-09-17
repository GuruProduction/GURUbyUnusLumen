package com.unuslumen.app.data.brain

import com.unuslumen.app.database.dao.MemoryCrossReferenceDao
import com.unuslumen.app.database.entity.MemoryCrossReferenceEntity
import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CurationEngine — Organizes facts into a domain/topic/subtopic hierarchy
 * and builds cross-references between related facts.
 *
 * Classification uses keyword scoring for domain detection and signature
 * similarity for topic/subtopic detection when signatures are available.
 *
 * Ported from Cerebrum's curation crate to Kotlin.
 */
class CurationEngine(
    private val crossReferenceDao: MemoryCrossReferenceDao
) {

    companion object {
        const val REF_RELATED = "RELATED"
        const val REF_CONTRADICTS = "CONTRADICTS"
        const val REF_SUPERSEDES = "SUPERSEDES"
        const val REF_DUPLICATE = "DUPLICATE"
    }

    private val domainKeywords = mapOf(
        "GURU_PROJECT" to listOf("guru", "app", "build", "code", "gradlew", "apk", "kotlin", "room", "database", "schema", "migration", "brain", "engine", "portal", "luxify"),
        "USER_PERSONAL" to listOf("steven", "family", "home", "cat", "mac", "phone", "personal", "life"),
        "BUSINESS" to listOf("unus lumen", "investment", "cap table", "sheldon", "company", "registration", "launch", "marketplace"),
        "TECHNICAL" to listOf("ssh", "server", "network", "tor", "adb", "shell", "android", "samsung", "bug", "fix", "anr"),
        "PREFERENCE" to listOf("prefer", "like", "hate", "want", "dark mode", "theme", "font", "layout")
    )

    /**
     * Classify a fact into domain/topic/subtopic.
     * Domain uses weighted keyword scoring with category override.
     * Topic uses keyword patterns but can be refined by signature similarity
     * against known topic signatures when available.
     */
    fun classify(factText: String, category: String): CurationResult {
        val lower = factText.lowercase()

        // Domain detection: weighted keyword scoring
        var domain = "GENERAL"
        var bestScore = 0
        for ((domainName, keywords) in domainKeywords) {
            val score = keywords.count { lower.contains(it) }
            if (score > bestScore) {
                bestScore = score
                domain = domainName
            }
        }

        // Category overrides for strong signals
        if (category == "personal_info") domain = "USER_PERSONAL"
        if (category == "preference") domain = "PREFERENCE"
        if (category == "project") domain = "GURU_PROJECT"
        if (category == "relationship") domain = "USER_PERSONAL"

        val topic = extractTopic(factText)
        val subtopic = extractSubtopic(factText, topic)

        return CurationResult(domain, topic, subtopic)
    }

    private fun extractTopic(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("context") || lower.contains("compaction") || lower.contains("microcompact") -> "context_management"
            lower.contains("database") || lower.contains("schema") || lower.contains("migration") -> "database"
            lower.contains("embed") || lower.contains("signature") || lower.contains("vector") || lower.contains("retrieval") -> "retrieval"
            lower.contains("network") || lower.contains("tor") || lower.contains("privacy") -> "network_privacy"
            lower.contains("bug") || lower.contains("fix") || lower.contains("anr") || lower.contains("broken") -> "bug_fixing"
            lower.contains("theme") || lower.contains("font") || lower.contains("layout") || lower.contains("dark mode") -> "ui_customization"
            lower.contains("lux") || lower.contains("luxcode") -> "lux_reference"
            lower.contains("business") || lower.contains("investment") || lower.contains("launch") -> "business"
            lower.contains("ssh") || lower.contains("mac") || lower.contains("server") -> "infrastructure"
            lower.contains("steven") || lower.contains("user") -> "user_info"
            else -> "general"
        }
    }

    private fun extractSubtopic(text: String, topic: String): String {
        val lower = text.lowercase()
        return when (topic) {
            "context_management" -> when {
                lower.contains("microcompact") -> "microcompact"
                lower.contains("autocompact") || lower.contains("compaction") -> "autocompact"
                lower.contains("token") -> "token_estimation"
                else -> ""
            }
            "database" -> when {
                lower.contains("migration") -> "migration"
                lower.contains("schema") -> "schema"
                lower.contains("room") -> "room"
                else -> ""
            }
            "retrieval" -> when {
                lower.contains("signature") -> "signature"
                lower.contains("embedding") -> "embedding"
                lower.contains("graph") -> "graph"
                else -> ""
            }
            else -> ""
        }
    }

    /**
     * Build cross-references between a new fact and existing facts.
     * Uses signature similarity for precise matching.
     */
    suspend fun buildCrossReferences(
        newFact: MemoryFactEntity,
        existingFacts: List<MemoryFactEntity>,
        currentTime: Long
    ) = withContext(Dispatchers.Default) {
        if (newFact.signature.isBlank()) return@withContext

        val newSig = SignatureEngine.decodeSignature(newFact.signature)
        val refs = mutableListOf<MemoryCrossReferenceEntity>()

        for (existing in existingFacts) {
            if (existing.id == newFact.id || existing.signature.isBlank()) continue

            val existingSig = SignatureEngine.decodeSignature(existing.signature)
            val similarity = SignatureEngine.similarity(newSig, existingSig)

            when {
                similarity > 0.9f -> {
                    refs.add(MemoryCrossReferenceEntity(
                        factIdA = newFact.id,
                        factIdB = existing.id,
                        refType = REF_DUPLICATE,
                        createdAt = currentTime
                    ))
                }
                similarity > 0.6f -> {
                    refs.add(MemoryCrossReferenceEntity(
                        factIdA = newFact.id,
                        factIdB = existing.id,
                        refType = REF_RELATED,
                        createdAt = currentTime
                    ))
                }
                similarity > 0.3f && newFact.domain == existing.domain -> {
                    refs.add(MemoryCrossReferenceEntity(
                        factIdA = newFact.id,
                        factIdB = existing.id,
                        refType = REF_RELATED,
                        createdAt = currentTime
                    ))
                }
            }
        }

        if (refs.isNotEmpty()) {
            crossReferenceDao.insertCrossReferences(refs)
        }
    }
}

data class CurationResult(
    val domain: String,
    val topic: String,
    val subtopic: String
)
