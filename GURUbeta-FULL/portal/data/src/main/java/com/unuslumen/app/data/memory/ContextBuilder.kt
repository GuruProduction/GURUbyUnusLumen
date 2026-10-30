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
 *
 * Every call also records what it injected, through the optional ledger. That
 * record is what makes retrieval falsifiable: without it the only evidence is a
 * handful of hand-written queries, and every change to the brain is unfalsifiable
 * on real data. The ledger is optional so unit tests and callers that predate it
 * keep working unchanged, and a ledger failure never breaks a send.
 */
@Factory
class ContextBuilder(
    private val brainService: BrainService,
    private val toolResultDao: com.unuslumen.app.database.dao.ToolResultDao,
    /**
     * NOT optional, and that is the fix.
     *
     * This was `InjectionLedger? = null`. Two separate things then made the
     * ledger silently dead:
     *
     *  1. `@ComponentScan` generates this class's binding from its constructor
     *     and passes only the non-defaulted parameters. A nullable with a default
     *     is treated as "caller may omit", so the generated `factory {
     *     ContextBuilder(brainService=get(), toolResultDao=get()) }` left the
     *     ledger null. The hand-written `factory { ContextBuilder(get(), get(),
     *     get()) }` in AiDataModule was dead code — KSP's generated module
     *     overrides it, and nothing warns when a scanned class is also declared
     *     by hand.
     *  2. A null ledger is indistinguishable from "feature off", so the preamble
     *     built and recorded nothing, the table stayed empty, and every test that
     *     did not assert on row counts passed.
     *
     * Requiring it means the generated binding must resolve it or the app fails
     * to start, which is the failure anyone would notice immediately.
     */
    private val injectionLedger: InjectionLedger,
) {

    /**
     * Build the preamble and record what went into it.
     *
     * [conversationId] is required for the ledger to attribute rows; when it is
     * blank the preamble is still built and nothing is recorded, which is the
     * behaviour callers that do not have a conversation yet expect.
     */
    suspend fun buildPreamble(
        query: String,
        humanName: String = "",
        currentMessages: List<AiMessage> = emptyList(),
        conversationId: String = "",
    ): RetrievedContext = withContext(Dispatchers.Default) {
        // ONE STORE for REMEMBERED content: fact tier rides the brain; message
        // tier reads REHYDRATED chat drawer rows through BrainService's marker
        // search (historical rows persist in the vault as rehydration source)
        // and stays correct even while drawer rows age out of recent search.
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

        // Record what reached the model, before returning. Best-effort and never
        // fatal: a ledger write cannot break a send.
        if (conversationId.isNotBlank()) {
            val injected = mutableListOf<InjectedMemory>()
            facts.forEachIndexed { rank, fact ->
                injected += InjectedMemory(
                    memoryId = fact.id,
                    lane = com.unuslumen.app.database.entity.MemoryInjectionEntity.LANE_FACT,
                    rankInLane = rank + 1,
                    score = fact.confidence,
                    content = fact.fact,
                )
            }
            messages.forEachIndexed { rank, msg ->
                injected += InjectedMemory(
                    memoryId = msg.id,
                    lane = com.unuslumen.app.database.entity.MemoryInjectionEntity.LANE_MESSAGE,
                    rankInLane = rank + 1,
                    score = 0f,
                    content = msg.content,
                )
            }
            runCatching {
                injectionLedger.recordInjections(
                    query = query,
                    conversationId = conversationId,
                    injections = injected,
                )
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
