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
 * ContextBuilder — the context preamble builder, three lanes, each read from the
 * store that owns its rows.
 *
 *  - FACT lane: the Cerebrum brain's SEARCH frame. Memories live there and
 *    nowhere else.
 *  - MESSAGE lane: Room's messages table. Raw chat has lived there since the
 *    one-store boundary reset; the brain stopped holding it.
 *  - TOOL-RESULT lane: Room's tool_results table, for the same reason.
 *
 * This used to claim one store and route every lane through the brain, filtering
 * hits on text markers ("message conv=", "tool result: ") to separate them. Those
 * markers are written by a one-shot migration and by nothing else, so with a
 * fresh install both filters matched nothing and two of three lanes were
 * permanently empty while looking correctly wired. See issue 418, defect 3.
 *
 * Every call records what it injected, through a ledger that is REQUIRED rather
 * than optional. That record is what makes retrieval falsifiable: without it the
 * only evidence is a handful of hand-written queries, and every change to the
 * brain is unfalsifiable on real data. A ledger failure is still swallowed,
 * because an observation log that can break a send is worse than none.
 */
@Factory
class ContextBuilder(
    private val brainService: BrainService,
    private val toolResultDao: com.unuslumen.app.database.dao.ToolResultDao,
    private val messageDao: com.unuslumen.app.database.dao.MessageDao,
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
        // THE MESSAGE AND TOOL-RESULT LANES READ ROOM, AND THAT IS THE FIX.
        //
        // Both used to call BrainService and then filter the brain's hits on a
        // text marker ("message conv=", "tool result: "). Nothing in the vault
        // carries those markers any more: the one-store boundary reset moved raw
        // chat and tool results into Room and stopped the brain holding them, so
        // both filters ran over a store that could not match. Every one of the
        // 187 recorded injections was lane "fact" for that reason, while the
        // wiring to the ledger looked correct the whole time.
        //
        // A marker filter is the wrong mechanism regardless of which store it
        // reads. It asks the retrieval engine to find rows by their serialised
        // prefix, which is a question about storage format rather than about
        // relevance. Reading each lane from the store that owns its rows removes
        // the contract that could drift.
        val factsDeferred = async {
            runCatching {
                brainService.search(query, topK = 20)
            }.getOrNull() ?: emptyList()
        }

        val messagesDeferred = async {
            if (query.isBlank()) {
                emptyList()
            } else {
                runCatching {
                    messageDao.searchMessagesLike(query).take(15).map { row ->
                        com.unuslumen.app.domain.memory.ConversationMessage(
                            id = row.id,
                            conversationId = row.conversationId,
                            role = row.role,
                            content = row.content,
                            timestamp = row.timestamp,
                        )
                    }
                }.getOrNull() ?: emptyList()
            }
        }

        val toolResultsDeferred = async {
            if (query.length < 4) {
                emptyList()
            } else {
                runCatching {
                    toolResultDao.searchToolResultsLike(query).take(3).map { row ->
                        BrainService.ToolResultContextHit(
                            toolName = row.toolName,
                            result = row.resultText,
                            timestamp = row.timestamp,
                            stale = (System.currentTimeMillis() - row.timestamp) > 24L * 60 * 60 * 1000,
                        )
                    }
                }.getOrNull() ?: emptyList()
            }
        }

        val messages = messagesDeferred.await()
        val facts = factsDeferred.await()
        val toolResults = toolResultsDeferred.await()

        val preamble = buildString {
            if (facts.isNotEmpty()) {
                append("Context from your memory:\n")
                facts.forEach { hit ->
                    val fact = hit.fact
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
        //
        // THE SCORE IS THE BRAIN'S MATCH SCORE, NOT THE FACT'S CONFIDENCE.
        // This recorded `fact.confidence`, which is a property of the memory
        // rather than of the retrieval: a fact stored with confidence 0.95
        // scored 0.95 whether it was the perfect hit or completely unrelated.
        // 81.8% of rank-1 rows read exactly 1.0 as a result, and every row from
        // an empty query read exactly 0.5, because those are the confidences of
        // the facts involved. Any analysis over that column was measuring the
        // vault's confidence distribution while claiming to measure retrieval.
        //
        // The lane score now comes from DomainSearchResult.score, which is the
        // brain's own SEARCH rank. The confidence a fact was stored with is
        // already rendered in the preamble text for the model to read, so
        // nothing is lost by keeping it out of the measurement column.
        if (conversationId.isNotBlank()) {
            val injected = mutableListOf<InjectedMemory>()
            facts.forEachIndexed { rank, hit ->
                injected += InjectedMemory(
                    memoryId = hit.fact.id,
                    lane = com.unuslumen.app.database.entity.MemoryInjectionEntity.LANE_FACT,
                    rankInLane = rank + 1,
                    score = hit.score,
                    content = hit.fact.fact,
                )
            }
            messages.forEachIndexed { rank, msg ->
                injected += InjectedMemory(
                    memoryId = msg.id,
                    lane = com.unuslumen.app.database.entity.MemoryInjectionEntity.LANE_MESSAGE,
                    rankInLane = rank + 1,
                    // A LIKE match has no relevance score to report. Zero is the
                    // honest value for "this lane does not rank", and it is
                    // distinguishable from a real match score of zero because
                    // the lane column says which lane produced the row.
                    score = 0f,
                    content = msg.content,
                )
            }
            toolResults.forEachIndexed { rank, hit ->
                injected += InjectedMemory(
                    // A tool result row is identified by tool name and time:
                    // its id is not carried on the hit, and inventing one would
                    // put an id in the ledger that resolves to nothing.
                    memoryId = "tool:${hit.toolName}:${hit.timestamp}",
                    lane = com.unuslumen.app.database.entity.MemoryInjectionEntity.LANE_TOOL_RESULT,
                    rankInLane = rank + 1,
                    score = 0f,
                    content = hit.result,
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
            facts = facts.map { it.fact },
            threads = emptyList(),
            preamble = preamble
        )
    }
}
