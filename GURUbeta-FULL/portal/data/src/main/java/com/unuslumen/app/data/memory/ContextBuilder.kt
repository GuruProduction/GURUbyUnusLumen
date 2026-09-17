package com.unuslumen.app.data.memory

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.database.entity.ToolResultEntity
import com.unuslumen.app.domain.memory.RetrievedContext
import com.unuslumen.app.domain.model.AiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class ContextBuilder(
    private val embeddingService: LocalEmbeddingService,
    private val vectorSearchEngine: VectorSearchEngine,
    private val brainService: BrainService,
    private val toolResultDao: ToolResultDao
) {

    suspend fun buildPreamble(query: String, humanName: String = "", currentMessages: List<AiMessage> = emptyList()): RetrievedContext = withContext(Dispatchers.Default) {
        // Use the existing embedding for message search (VectorSearchEngine still handles messages)
        // Wrapped in try-catch so embedding model failure doesn't break the whole preamble
        val embeddingResult = try {
            embeddingService.generateEmbedding(query)
        } catch (e: Exception) {
            android.util.Log.w("guru", "Embedding generation failed in ContextBuilder: ${e.message}")
            Result.failure(e)
        }

        // Message search uses the old VectorSearchEngine (it indexes message embeddings)
        val messagesDeferred = async {
            if (embeddingResult.isFailure) {
                emptyList()
            } else {
                val embedding = embeddingResult.getOrNull() ?: emptyList()
                runCatching {
                    vectorSearchEngine.searchMessages(query = query, queryEmbedding = embedding)
                        .map { it.message.toDomain() }
                }.getOrNull() ?: emptyList()
            }
        }

        // Fact search uses BrainService's four-tier retrieval (FTS5, signature, DVM, graph)
        // with recency boost. Pulls 20 facts so GURU has enough context to work with.
        val factsDeferred = async {
            runCatching {
                brainService.search(query, topK = 20)
            }.getOrNull()?.map { it.fact.toDomain() } ?: emptyList()
        }

        // Tool results RAG injection — only if query is long enough to be meaningful
        val toolResultsDeferred = async {
            if (query.length < 4) {
                emptyList()
            } else {
                runCatching {
                    val ftsQuery = "\"$query\" OR ${query.trim()}"
                    toolResultDao.searchToolResultsFtsWithScore(ftsQuery, 3, -1.0)
                }.getOrNull()?.filter { result ->
                    // Filter out expired TTL entries
                    val ttl = result.ttlMinutes
                    if (ttl != null) {
                        val ageMinutes = (System.currentTimeMillis() - result.timestamp) / (60 * 1000)
                        ageMinutes <= ttl + 1440 // Keep stale entries within 24h grace period for preamble
                    } else {
                        true
                    }
                }?.filter { result ->
                    // Duplicate detection: skip results already in the microcompact window
                    // Compare by tool name and timestamp (exact match)
                    val alreadyInContext = currentMessages.filterIsInstance<AiMessage.ToolCall>()
                        .any { msg -> msg.name == result.toolName && msg.time == result.timestamp }
                    !alreadyInContext
                } ?: emptyList()
            }
        }

        val messages = messagesDeferred.await()
        val facts = factsDeferred.await()
        val toolResults = toolResultsDeferred.await()

        val preamble = buildString {
            if (facts.isNotEmpty()) {
                append("Context from your memory:\n")
                facts.forEach { fact ->
                    val domainStr = if (fact.domain.isNotBlank()) ", domain: ${fact.domain}" else ""
                    val layerStr = if (fact.layer.isNotBlank()) ", layer: ${fact.layer}" else ""
                    append("Memory [${fact.category}] (confidence: ${(fact.confidence * 100).toInt()}%${domainStr}${layerStr}): ${fact.fact}\n")
                }
                append("\n")
            }

            if (messages.isNotEmpty()) {
                append("Relevant past conversation context:\n")
                messages.takeLast(15).forEach { msg ->
                    val role = when (msg.role) {
                        "user" -> humanName.ifBlank { "User" }
                        "assistant" -> "Guru"
                        "tool" -> "Tool"
                        else -> msg.role
                    }
                    append("$role: ${msg.content.take(500)}\n")
                }
                append("\n")
            }

            if (toolResults.isNotEmpty()) {
                append("Past tool results that may be relevant:\n")
                toolResults.forEach { result ->
                    val ageMinutes = (System.currentTimeMillis() - result.timestamp) / (60 * 1000)
                    val ttl = result.ttlMinutes
                    val staleNote = if (ttl != null && ageMinutes > ttl) {
                        " [stale data, consider re-calling ${result.toolName}]"
                    } else ""
                    append("Tool: ${result.toolName} (age: ${ageMinutes}min${staleNote})\n")
                    append("Result: ${result.result.take(1000)}\n")
                }
                append("\n")
            }

            if (isEmpty()) {
                append("No relevant past context found.\n")
            }
        }

        RetrievedContext(
            messages = messages,
            facts = facts,
            threads = emptyList(),
            preamble = preamble
        )
    }
}
