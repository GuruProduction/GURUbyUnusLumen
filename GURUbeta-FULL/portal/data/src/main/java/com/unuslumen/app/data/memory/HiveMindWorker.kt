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
 * Prompts never come from source constants: HivePromptFetcher carries
 * server-authored shapes with structured constructs as offline fallback.
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
     * Dedup pass: signature-space exactness FIRST (cheap, deterministic), then
     * one LLM judgement over the whole batch when 2+ candidates share shape.
     * Any model-side ambiguity (broken JSON, empty reply, no verdict) keeps
     * all candidates: an uncertain judge never deletes a fresh memory.
     */
    private suspend fun deduplicateAgainstExisting(
        appContext: Context,
        candidates: List<MemoryFact>
    ): List<MemoryFact> {
        if (candidates.isEmpty()) return candidates

        // Tier 1: literal-duplicate kill without any model spend.
        val existingFactsForCategory = candidates
            .map { it.category }
            .distinct()
            .flatMap { memoryRepository.getFactsByCategory(it) }
            .map { it.fact.trim().lowercase() }
            .toSet()
        val literalNovel = candidates.filter { it.fact.trim().lowercase() !in existingFactsForCategory }
        if (existingFactsForCategory.isEmpty() || literalNovel.size <= 1) return literalNovel

        // Tier 2: batch-LLM judgement, exactness only, never destructive.
        val dedupPrompt = HivePromptFetcher.factDeduplication(appContext)
        val existingList = existingFactsForCategory.take(50)
            .joinToString("\n") { f -> "- $f" }
        val candidatesList = literalNovel
            .mapIndexed { index, fact -> "$index. ${fact.fact}" }
            .joinToString("\n")
        val judgementBody = (
            "EXISTING FACTS:\n$existingList\n\nNEW FACTS (index numbers matter):\n$candidatesList\n\n" +
                "Reply STRICT JSON ONLY: {\"duplicates_of_existing\":[<new-fact index>],\"internal_duplicates\":[<index>,<index>]}. " +
                "Use [] for both lists when everything is novel. No prose outside the JSON."
            )

        val result = aiRepository.sendSystemPrompt(
            systemPrompt = dedupPrompt,
            userPrompt = judgementBody,
        )
        val keepAll = candidates
        val judgement: DedupJudgement = when (result) {
            is PortalResult.Success -> {
                try {
                    val trimmed = result.data.trim()
                    val jsonStart = trimmed.indexOf('{')
                    val jsonEnd = trimmed.lastIndexOf('}') + 1
                    if (jsonStart < 0 || jsonEnd <= jsonStart) return keepAll
                    val parsed = json.decodeFromString(DedupJudgement.serializer(), trimmed.substring(jsonStart, jsonEnd))
                    parsed
                } catch (e: Exception) {
                    return keepAll
                }
            }
            else -> return keepAll
        }

        val doomedInternal = judgement.internal_duplicates
            .filter { it.size >= 2 }
            .flatMap { pair -> pair.drop(1) }
            .toSet()
        val doomedExisting = judgement.duplicates_of_existing
            .filter { it in literalNovel.indices }
            .toSet()
        return literalNovel.filterIndexed { index, _ -> index !in doomedInternal && index !in doomedExisting }
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
