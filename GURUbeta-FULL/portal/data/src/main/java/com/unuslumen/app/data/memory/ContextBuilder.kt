// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.data.brain.cerebrum.CerebrumBrainApi
import com.unuslumen.app.domain.memory.RetrievedContext
import com.unuslumen.app.domain.model.AiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

/**
 * ContextBuilder — the context preamble builder. ONE STORE: every tier here
 * (facts and messages) dispatches real brain frames through CerebrumBrainApi
 * and BrainService. There is no Kotlin second store and no legacy Room tier
 * anywhere in the memory preamble: tool-results rows also ride the brain as
 * their upsert content rows (search hits carry both the tool name and text).
 */
@Factory
class ContextBuilder(
    private val brainService: BrainService,
    private val toolResultDao: com.unuslumen.app.database.dao.ToolResultDao
) {

    suspend fun buildPreamble(query: String, humanName: String = "", currentMessages: List<AiMessage> = emptyList()): RetrievedContext = withContext(Dispatchers.Default) {
        // ONE STORE: every lane searches brain frames, no embedding pre-pass,
        // no VectorSearchEngine, no room dao reads.
        val messagesDeferred = async {
            runCatching {
                brainService.conversationSearch(query, topK = 15)
            }.getOrNull() ?: emptyList()
        }

        // Fact search: the real SEARCH frame, already-domain hits.
        val factsDeferred = async {
            runCatching {
                brainService.search(query, topK = 20)
            }.getOrNull()?.map { it.fact } ?: emptyList()
        }

        // Tool results lane: real brain rows ('tool result:' marker content);
        // microcompact dedup on tool name + timestamp against the context.
        val toolResultsDeferred = async {
            if (query.length < 4) {
                emptyList()
            } else {
                runCatching {
                    brainService.toolResultSearch(query, topK = 3)
                }.getOrNull() ?: emptyList()
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
                    // Brain tool-result rows carry their staleness on the hit
                    // itself (computed row-side at the timestamp parse); the
                    // Room TTL column went out with the one-store cut.
                    if (result.stale) {
                        append("Tool: ${result.toolName} stale, consider re-calling; age: ${ageMinutes}min\n")
                        append("Result: ${result.result.take(1000)}\n")
                    } else {
                        append("Tool: ${result.toolName} (age: ${ageMinutes}min)\n")
                        append("Result: ${result.result.take(1000)}\n")
                    }
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
