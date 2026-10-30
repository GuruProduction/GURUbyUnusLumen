// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import android.util.Log
import com.unuslumen.app.database.dao.MemoryInjectionDao
import com.unuslumen.app.database.entity.MemoryInjectionEntity
import java.util.UUID
import org.koin.core.annotation.Single

/**
 * InjectionLedger — records what the preamble believed was relevant, so
 * retrieval quality can be measured on real data instead of on test pairs
 * somebody wrote.
 *
 * The reason this exists, in one line: without it, the only evidence that
 * retrieval works is a handful of hand-picked queries, and every change to the
 * brain is unfalsifiable. With it, ordinary conversation produces labels for
 * free.
 *
 * Three signals it captures:
 *
 *  1. Every (query, memory, lane, rank, score) the preamble injected.
 *  2. A memory injected and later visibly used, by recording confirmation.
 *  3. A user restating a stored fact the preamble did NOT inject. That is a
 *     recall failure the user produced by accident, and it is the most valuable
 *     row in the table because it comes from the real distribution and costs
 *     nothing to collect.
 *
 * Everything here is best-effort. The ledger must never break a send: if a write
 * fails, the turn proceeds and the failure is logged.
 */
@Single
class InjectionLedger(
    private val injectionDao: MemoryInjectionDao
) {
    private val tag = "guru_ledger"

    /**
     * Log without ever throwing.
     *
     * THE BUG THIS FIXES. Every failure path in this class called Log.w inside
     * its own `onFailure`. On the JVM (unit tests, and any non-Android host) the
     * android.util.Log stub throws "not mocked", so the failure handler threw out
     * of the runCatching it was meant to make safe and took the whole call with
     * it. A ledger write error would therefore crash the send it existed to
     * observe, which is the exact opposite of best-effort.
     */
    private fun logWarn(message: String) {
        runCatching { Log.w(tag, message) }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(tag, message) }
    }

    /**
     * Record every memory the preamble put in front of the model for this turn.
     *
     * Called from the send path with the exact hits that made it into the
     * preamble, in lane order, so rank is real rather than reconstructed.
     */
    suspend fun recordInjections(
        query: String,
        conversationId: String,
        injections: List<InjectedMemory>,
    ) {
        if (injections.isEmpty()) return
        val now = System.currentTimeMillis()
        val capped = MemoryInjectionEntity.capQuery(query)
        val rows = injections.map { injected ->
            MemoryInjectionEntity(
                id = UUID.randomUUID().toString(),
                memoryId = injected.memoryId,
                query = capped,
                lane = injected.lane,
                rankInLane = injected.rankInLane,
                score = injected.score,
                conversationId = conversationId,
                timestamp = now,
            )
        }
        runCatching { injectionDao.insertAll(rows) }.onFailure {
            // A ledger write must never fail a turn. Log it and carry on.
            logWarn("recordInjections failed for conv=$conversationId: ${it.message}")
        }
    }

    /**
     * A memory the preamble injected was later used: echoed by the user, quoted
     * by the model, or acted on. Confirms the hit.
     */
    suspend fun confirmUsed(conversationId: String, memoryId: String) {
        runCatching {
            injectionDao.markConfirmedUsed(conversationId, memoryId, System.currentTimeMillis())
        }.onFailure {
            logWarn("confirmUsed failed: ${it.message}")
        }
    }

    /**
     * The user restated a stored memory while the preamble had not injected it.
     *
     * This is the recall-failure signal. It records a row with lane "restated" so
     * the failure count sits in the same table as the successes and can be read
     * with the same queries.
     */
    suspend fun recordRecallFailure(
        query: String,
        conversationId: String,
        memoryId: String,
        score: Float,
    ) {
        runCatching {
            // One row per (conversation, memory) failure; a user restating the
            // same thing five times is one retrieval failure, not five.
            if (injectionDao.countRestated(conversationId, memoryId) > 0) return
            injectionDao.insert(
                MemoryInjectionEntity(
                    id = UUID.randomUUID().toString(),
                    memoryId = memoryId,
                    query = MemoryInjectionEntity.capQuery(query),
                    lane = LANE_RESTATED,
                    rankInLane = 0,
                    score = score,
                    conversationId = conversationId,
                    timestamp = System.currentTimeMillis(),
                    restatedWithoutInjection = true,
                )
            )
            logInfo("recall failure recorded for conv=$conversationId memory=${memoryId.take(12)}")
        }.onFailure {
            logWarn("recordRecallFailure failed: ${it.message}")
        }
    }

    /** Was this memory already put in front of the model in this conversation? */
    suspend fun wasInjected(conversationId: String, memoryId: String): Boolean =
        runCatching { injectionDao.countFor(conversationId, memoryId) > 0 }.getOrDefault(false)

    /**
     * Cross-check what the preamble DID inject against the memories the brain
     * says are relevant to this turn.
     *
     * Every relevant memory the preamble missed is a recall failure, unless the
     * user has already been shown it in this conversation (in which case the
     * model still has it from earlier in the transcript and nothing was lost).
     * That distinction is what makes this signal trustworthy: it only fires when
     * the conversation genuinely did not contain the memory.
     */
    suspend fun recordMissedRelevant(
        query: String,
        conversationId: String,
        relevant: List<InjectedMemory>,
        injectedIds: Set<String>,
    ) {
        for (candidate in relevant) {
            if (candidate.memoryId in injectedIds) continue
            recordRecallFailure(
                query = query,
                conversationId = conversationId,
                memoryId = candidate.memoryId,
                score = candidate.score,
            )
        }
    }

    /** Prune on the same 30-day horizon as tool results. */
    suspend fun prune() {
        runCatching { injectionDao.pruneLedger(System.currentTimeMillis()) }.onFailure {
            logWarn("ledger prune failed: ${it.message}")
        }
    }

    /** Lane precision over a window, for comparing retrieval lanes on real data. */
    suspend fun precisionByLane(sinceMillis: Long) =
        runCatching { injectionDao.precisionByLane(sinceMillis) }.getOrDefault(emptyList())

    /** Recall failures in a window: restatements the preamble should have caught. */
    suspend fun recallFailuresSince(sinceMillis: Long): Int =
        runCatching { injectionDao.recallFailuresSince(sinceMillis) }.getOrDefault(0)

    companion object {
        /** Lane name for a recall failure, matching the entity's lane vocabulary. */
        const val LANE_RESTATED = "restated"
    }
}

/**
 * One memory that reached the preamble, carrying enough to score it later.
 *
 * Kept Android-free so it can be built in a unit test on the JVM.
 */
data class InjectedMemory(
    val memoryId: String,
    val lane: String,
    val rankInLane: Int,
    val score: Float,
    /** The memory body, held in memory only for the miss check; never persisted. */
    val content: String,
)
