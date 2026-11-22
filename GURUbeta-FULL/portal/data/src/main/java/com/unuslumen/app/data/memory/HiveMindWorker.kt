// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.domain.model.PortalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

@Serializable
data class ExtractedFactsResponse(
    val facts: List<ExtractedFact>
)

@Serializable
data class ExtractedFact(
    val category: String,
    val fact: String,
    val confidence: Float
)

@Serializable
data class DiscoveredThreadsResponse(
    val threads: List<DiscoveredThread>
)

@Serializable
data class DiscoveredThread(
    val title: String,
    val summary: String,
    val conversationIds: List<String> = emptyList()
)

/** Dedup judge reply: indices of new facts shadowed by reality. */
@Serializable
data class DedupJudgement(
    val duplicates_of_existing: List<Int> = emptyList(),
    val internal_duplicates: List<List<Int>> = emptyList()
)

/**
 * One dedup decision in the shape the SERVED prompt asks for.
 *
 * The served row ends with its own output format: `action` of `merge`, `keep_both` or
 * `flag_contradiction`, with a merged statement, a confidence and an explanation. This
 * decodes exactly that and nothing else, because the served text is the only instruction
 * that reaches the model.
 *
 * Every field is defaulted so a partial answer decodes rather than throws. A judge that
 * answers nothing useful must not become a judge that drops a memory, and the caller
 * treats a missing action as "keep".
 */
@Serializable
data class DedupDecision(
    val action: String = "",
    val merged_fact: String? = null,
    val confidence: Float = 0f,
    val explanation: String = "",
)

/**
 * HiveMindWorker — the extraction pass for the memory brain. Runs every 15
 * minutes with ONLY battery-not-low (no network requirement, no idle
 * requirement; the one-store era's starved KEEP+idle chain was the F1 bug).
 * Policy UPDATE refreshes the constraints on every boot without resetting
 * the cadence, so an OLD starved periodic request from the 6 Oct era gets
 * upgraded in place.
 *
 * Per pass, over unprocessed conversations (after the newest hive marker):
 *  1. fact extraction (strict JSON contract; zero facts on a quiet pass is
 *     HONEST and the marker still lands, so nothing re-queues forever).
 *  2. thread discovery (one LLM pass over the whole unprocessed bunch).
 *  3. dedup pass skips cleanly when fewer than two candidate facts exist.
 *  4. the hive marker (brain-only) records the pass's reality.
 *
 * EVERY INSTRUCTION COMES FROM THE PUBLISHER, AND A LANE WITH NO INSTRUCTION DOES NOT RUN.
 *
 * There used to be a `HiveStructuredFallback` object in this package holding four prompt
 * bodies that the fetcher returned whenever the served field was blank. That was a second
 * authoring surface compiled into the app, and it was the only one that ever ran: the server
 * route filters on `category = 'hive_drive'`, no live row uses that category, so delivery
 * has always failed and the hardcoded text has been doing the work invisibly.
 *
 * The fallbacks are deleted. A blank field now means this device holds no instruction for
 * that lane, and the lane is skipped and says so. A model sent a transcript with no system
 * instruction writes its own instructions, and memory written from instructions the owner
 * never authored is worse than memory not written.
 */
class HiveMindWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val memoryRepository: MemoryRepository by inject()
    private val aiRepository: AiRepository by inject()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return withContext(Dispatchers.IO) {
            try {
                // Prompt gate FIRST: an instruction-free send stays illegal.
                val appContext = applicationContext
                android.util.Log.d(TAG, "boot-hive-fetch: pre-fetch prompt bundle")
                HivePromptFetcher.fetchAndCache(appContext)

                val unprocessedConversations = memoryRepository.getUnprocessedConversations()
                if (unprocessedConversations.isEmpty()) {
                    android.util.Log.d(TAG, "hive pass: no unprocessed conversations; nothing to do")
                    return@withContext androidx.work.ListenableWorker.Result.success()
                }

                var factsExtracted = 0
                var threadsDiscovered = 0

                // ---- PASS 1: fact extraction per conversation ----
                for (conversation in unprocessedConversations) {
                    val messages = memoryRepository.getMessagesByConversation(conversation.id)
                    if (messages.isEmpty()) continue

                    val conversationContent = buildString {
                        for (msg in messages) {
                            val roleLabel = when (msg.role) {
                                "user" -> "User"
                                "assistant" -> "Assistant"
                                "tool" -> "Tool"
                                else -> msg.role
                            }
                            append("$roleLabel: ${msg.content.take(2000)}\n")
                        }
                    }

                    val extractedFacts = extractFactsWithLLM(appContext, conversationContent, conversation.id)
                    if (extractedFacts.isNotEmpty()) {
                        val novelFacts = deduplicateAgainstExisting(appContext, extractedFacts)
                        memoryRepository.persistFacts(novelFacts)
                        factsExtracted += novelFacts.size
                    }
                }

                // ---- PASS 2: thread discovery across the whole unprocessed set ----
                val threads = discoverThreads(appContext, unprocessedConversations)
                if (threads.isNotEmpty()) {
                    memoryRepository.persistThreads(threads)
                    threadsDiscovered += threads.size
                }

                // ---- PASS 3: honest marker (brain-only, one per run) ----
                val lastConversationId = unprocessedConversations.lastOrNull()?.id ?: ""
                memoryRepository.markHiveProcessing(
                    conversationId = lastConversationId,
                    factsExtracted = factsExtracted,
                    threadsDiscovered = threadsDiscovered
                )
                android.util.Log.i(TAG, "hive pass done: facts=$factsExtracted threads=$threadsDiscovered convs=${unprocessedConversations.size}")

                androidx.work.ListenableWorker.Result.success()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "hive pass failed (attempt $runAttemptCount): ${e.message}")
                if (runAttemptCount < 3) {
                    androidx.work.ListenableWorker.Result.retry()
                } else {
                    androidx.work.ListenableWorker.Result.failure()
                }
            }
        }
    }

    private suspend fun extractFactsWithLLM(
        appContext: Context,
        conversationContent: String,
        conversationId: String
    ): List<MemoryFact> {
        val timestamp = System.currentTimeMillis()
        val systemPrompt = HivePromptFetcher.factExtraction(appContext)

        // NO INSTRUCTION, NO SEND. A transcript handed to a model with no system content
        // gets whatever instructions the model invents, and the facts that come back are
        // attributed to this device as though the owner had asked for them.
        if (systemPrompt.isBlank()) {
            android.util.Log.w(
                TAG,
                "extract lane skipped: no extraction instruction has been delivered to this device",
            )
            return emptyList()
        }

        val result = aiRepository.sendSystemPrompt(
            systemPrompt = systemPrompt,
            userPrompt = "TRANSCRIPT START\n$conversationContent\nTRANSCRIPT END",
        )

        return when (result) {
            is PortalResult.Success -> parseFactsResponse(result.data, timestamp, conversationId)
            else -> {
                android.util.Log.w(TAG, "extract pass silent: model unavailable for conv=$conversationId")
                emptyList()
            }
        }
    }

    private fun parseFactsResponse(responseText: String, timestamp: Long, conversationId: String): List<MemoryFact> {
        return try {
            val trimmed = responseText.trim()
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
            val jsonContent = trimmed.substring(jsonStart, jsonEnd)
            val extracted = json.decodeFromString(ExtractedFactsResponse.serializer(), jsonContent)
            extracted.facts
                .filter { it.confidence >= 0.7f && it.fact.isNotBlank() }
                .map { fact ->
                    MemoryFact(
                        id = "fact_${timestamp}_${fact.fact.hashCode()}",
                        category = fact.category,
                        fact = fact.fact,
                        confidence = fact.confidence,
                        sourceConversationIds = listOf(conversationId),
                        extractedDate = timestamp
                    )
                }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fact response unparseable (model broke the contract): ${e.message}")
            emptyList()
        }
    }

    /**
     * Dedup pass: exactness FIRST (cheap, deterministic), then one LLM judgement
     * over the whole batch when 2+ candidates look novel.
     * Any model-side ambiguity (broken JSON, empty reply, no verdict) keeps all
     * candidates: an uncertain judge never drops a fresh memory.
     *
     * IT COMPARES AGAINST THE RETIRED ROWS TOO, AND THAT IS THE ANTI-RESURRECTION
     * FIX ON THIS SIDE.
     *
     * The comparison set used to be the live listing only. A deleted fact is absent
     * from a live listing, so the same sentence looked novel, and the write that
     * followed resolved to the id the brain derives from that content: the row the
     * deletion had archived. The brain now refuses to reactivate a retired id, so
     * the outcome improved from "silently undeleted" to "the pass reports a store
     * failure", which is still wrong.
     *
     * Seeing the archive means a deleted fact is recognised as already known and is
     * never written again. The refusal remains a backstop for a caller that reaches
     * the store another way, rather than the mechanism this pass depends on.
     */
    private suspend fun deduplicateAgainstExisting(
        appContext: Context,
        candidates: List<MemoryFact>
    ): List<MemoryFact> {
        if (candidates.isEmpty()) return candidates

        // Tier 1: exact-duplicate kill without any model spend, over the live rows
        // and the retired ones. An empty archive is the ordinary case on a fresh
        // install and costs one call.
        val liveFacts = candidates
            .map { it.category }
            .distinct()
            .flatMap { memoryRepository.getFactsByCategory(it) }
            .map { it.fact.trim().lowercase() }
            .toSet()
        val retiredFacts = runCatching {
            memoryRepository.getArchivedFactsForDeduplication()
                .map { archived -> archived.fact.trim().lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
        val existingFactsForCategory = liveFacts + retiredFacts

        val literalNovel = candidates.filter { it.fact.trim().lowercase() !in existingFactsForCategory }
        if (existingFactsForCategory.isEmpty() || literalNovel.size <= 1) return literalNovel

        // Tier 2: the model judges each candidate against what the brain already holds.
        //
        // ONE CANDIDATE AT A TIME, BECAUSE THE SERVED PROMPT ASKS ABOUT ONE PAIR. The row
        // `guru-reflection-fact-deduplication` is written as a pairwise comparison: it ends
        // with an output format of `{action, merged_fact, confidence, explanation}`. The
        // worker used to send it a numbered batch and demand
        // `{duplicates_of_existing, internal_duplicates}` instead, so the system prompt and
        // the user prompt described two different replies, the model answered one of them,
        // the parser read the other, found nothing, and every pass concluded everything was
        // novel. That is the silent half of the resurrection defect.
        //
        // The fix is not a better fallback. It is asking the question the served prompt
        // actually describes, one candidate at a time.
        val dedupPrompt = HivePromptFetcher.factDeduplication(appContext)
        if (dedupPrompt.isBlank()) {
            android.util.Log.w(
                TAG,
                "dedup lane skipped: no deduplication instruction has been delivered to this device",
            )
            return literalNovel
        }

        val kept = mutableListOf<MemoryFact>()
        for (candidate in literalNovel) {
            val existingList = existingFactsForCategory.take(50).joinToString("\n") { "- $it" }
            val judgementBody =
                "EXISTING FACT:\n$existingList\n\nNEW FACT:\n${candidate.fact}"
            val result = aiRepository.sendSystemPrompt(
                systemPrompt = dedupPrompt,
                userPrompt = judgementBody,
            )
            val decision = when (result) {
                is PortalResult.Success -> decodeDedupDecision(result.data)
                // An unavailable judge keeps the memory. Dropping a fact because the model
                // could not be asked is the destructive reading of an absent answer.
                else -> null
            }
            when (decision?.action?.lowercase()) {
                // The served prompt says a merge combines the statements into one clear
                // contextual fact. That merged text is what gets remembered.
                "merge" -> {
                    val merged = decision.merged_fact?.trim().orEmpty()
                    if (merged.isNotBlank()) {
                        kept += candidate.copy(
                            fact = merged,
                            confidence = decision.confidence.takeIf { it > 0f } ?: candidate.confidence,
                        )
                    } else {
                        // A merge with no merged statement is not an instruction to drop
                        // anything, so the candidate is kept as written.
                        kept += candidate
                    }
                }
                // A contradiction is flagged to the owner and BOTH statements survive. The
                // brain must never quietly pick a winner between two things a person said.
                "flag_contradiction" -> {
                    android.util.Log.i(
                        TAG,
                        "dedup: contradiction flagged against an existing fact: ${decision.explanation}",
                    )
                    kept += candidate
                }
                // "keep_both", an unrecognised action, and no answer at all all land here.
                else -> kept += candidate
            }
        }
        return kept
    }

    /**
     * Decode one dedup decision, or null when the reply carries no usable answer.
     *
     * The reply is found by its first `{` and its last `}`, so a fenced or prefaced answer
     * still parses. Anything else is a judge that did not answer, which the caller treats as
     * "keep".
     */
    private fun decodeDedupDecision(responseText: String): DedupDecision? {
        return try {
            val trimmed = responseText.trim()
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}') + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null
            json.decodeFromString(DedupDecision.serializer(), trimmed.substring(jsonStart, jsonEnd))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dedup decision unparseable: ${e.message}")
            null
        }
    }

    /**
     * Thread discovery: one LLM pass over the conversation TITLES + fresh fact
     * bodies of the whole unprocessed set. Real ConversationThread rows persist
     * through the same repo brain lane, carrying their source conv ids.
     */
    private suspend fun discoverThreads(
        appContext: Context,
        conversations: List<com.unuslumen.app.domain.memory.Conversation>
    ): List<ConversationThread> {
        val discoveryPrompt = HivePromptFetcher.threadDiscovery(appContext)
        if (discoveryPrompt.isBlank()) {
            android.util.Log.w(
                TAG,
                "thread discovery lane skipped: no instruction has been delivered to this device",
            )
            return emptyList()
        }
        val conversationListing = conversations
            .joinToString("\n") { conv ->
                "CONV ${conv.id}\tTITLE: ${conv.title}\tMESSAGES: ${conv.messageCount}"
            }

        val result = aiRepository.sendSystemPrompt(
            systemPrompt = discoveryPrompt,
            userPrompt = "CONVERSATIONS:\n$conversationListing",
        )
        return when (result) {
            is PortalResult.Success -> try {
                val trimmed = result.data.trim()
                val jsonStart = trimmed.indexOf('{')
                val jsonEnd = trimmed.lastIndexOf('}') + 1
                if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
                val payload = json.decodeFromString(DiscoveredThreadsResponse.serializer(), trimmed.substring(jsonStart, jsonEnd))
                val timestampNow = System.currentTimeMillis()
                payload.threads
                    .filter { it.title.isNotBlank() && it.summary.isNotBlank() }
                    .map { discovered ->
                        ConversationThread(
                            id = "thread_${timestampNow}_${discovered.title.hashCode()}",
                            title = discovered.title,
                            summary = discovered.summary,
                            conversationIds = discovered.conversationIds,
                            createdDate = timestampNow
                        )
                    }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "thread discovery unparseable: ${e.message}")
                emptyList()
            }
            else -> emptyList()
        }
    }

    companion object {
        const val TAG = "guru_hive"
        private const val WORK_NAME = "hive_mind_periodic"
        private const val ONE_TIME_NAME = "hive_mind_one_time"

        fun scheduleOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<HiveMindWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<HiveMindWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_NAME)
        }
    }
}
