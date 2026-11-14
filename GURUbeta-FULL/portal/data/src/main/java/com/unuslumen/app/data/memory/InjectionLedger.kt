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
     * The most recent turn's injections, held in memory so the reply can be
     * checked against them when it arrives.
     *
     * WHY THIS EXISTS. `recordInjections` is called while the preamble is being
     * built; the assistant's reply does not exist until the model has finished
     * streaming, seconds or minutes later. The confirmation decision needs both,
     * so the injections have to survive the gap.
     *
     * It is held here rather than returned to the caller because the ledger is
     * the only component that needs it and passing it through the repository
     * interface would put a memory-measurement type in the domain layer for no
     * reason. Keyed by conversation because two conversations can be open at
     * once and a reply must be scored against its own preamble.
     *
     * Bounded: one entry per live conversation, and the map is cleared per turn.
     * A conversation abandoned mid-turn leaves one small entry behind, which is
     * why [MAX_PENDING_TURNS] exists as a ceiling rather than relying on
     * consumers to be tidy.
     */
    private val pendingTurns = java.util.concurrent.ConcurrentHashMap<String, PendingTurn>()

    private data class PendingTurn(
        val userQuery: String,
        val injected: List<InjectedMemory>,
    )

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
     * preamble, in lane order, so rank is real rather than reconstructed. The
     * same list is held for [completeTurn], which is where the confirmation
     * decision is made once a reply exists.
     */
    suspend fun recordInjections(
        query: String,
        conversationId: String,
        injections: List<InjectedMemory>,
    ) {
        if (injections.isEmpty()) return
        if (pendingTurns.size >= MAX_PENDING_TURNS) {
            // A conversation that never produced a reply should not pin memory
            // forever. Clearing wholesale is correct rather than arbitrary: every
            // entry here is at most one turn old, so nothing of lasting value is
            // lost and the next build repopulates its own.
            pendingTurns.clear()
        }
        pendingTurns[conversationId] = PendingTurn(userQuery = query, injected = injections)

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

    /**
     * Observe a finished turn: what was injected, what came back, what was asked.
     *
     * THE CALLER THAT WAS MISSING. `confirmUsed` existed with no caller, so
     * `confirmed_used_at` was NULL on every row and `precisionByLane` computed
     * zero for every lane no matter how good retrieval was. A ledger with no
     * observation is not a measurement, it is a table.
     *
     * Reads the injections held by [recordInjections] rather than taking them
     * again, so the caller cannot pass a different list from the one that was
     * actually recorded and quietly score a turn against the wrong memories.
     *
     * Runs the decision in [RecallSignals] and writes one confirmation per
     * memory the reply demonstrably drew on. Best-effort throughout: a ledger
     * fault is logged and the turn proceeds, because an observation log that can
     * break a conversation is worse than no observation log.
     */
    suspend fun completeTurn(conversationId: String, assistantReply: String) {
        val pending = pendingTurns.remove(conversationId) ?: return
        if (pending.injected.isEmpty() || assistantReply.isBlank()) return
        runCatching {
            val confirmedIds = RecallSignals.confirmationsFor(
                injected = pending.injected,
                assistantReply = assistantReply,
                userQuery = pending.userQuery,
            )
            for (memoryId in confirmedIds) {
                injectionDao.markConfirmedUsed(conversationId, memoryId, System.currentTimeMillis())
            }
            if (confirmedIds.isNotEmpty()) {
                logInfo("confirmed ${confirmedIds.size} of ${pending.injected.size} injected memories for conv=$conversationId")
            }
        }.onFailure {
            logWarn("completeTurn failed: ${it.message}")
        }
    }

    /**
     * Score a finished turn against an explicit list.
     *
     * Used by tests and by any caller that already holds the injections. The
     * production path uses [completeTurn], which cannot disagree with what was
     * recorded.
     */
    suspend fun recordTurnOutcome(
        conversationId: String,
        userQuery: String,
        injected: List<InjectedMemory>,
        assistantReply: String,
    ) {
        if (injected.isEmpty() || assistantReply.isBlank()) return
        runCatching {
            val confirmedIds = RecallSignals.confirmationsFor(
                injected = injected,
                assistantReply = assistantReply,
                userQuery = userQuery,
            )
            for (memoryId in confirmedIds) {
                injectionDao.markConfirmedUsed(conversationId, memoryId, System.currentTimeMillis())
            }
            if (confirmedIds.isNotEmpty()) {
                logInfo("confirmed ${confirmedIds.size} of ${injected.size} injected memories for conv=$conversationId")
            }
        }.onFailure {
            logWarn("recordTurnOutcome failed: ${it.message}")
        }
    }

    /**
     * Check a new user message against memories this conversation already
     * showed the model, and record any the human restated anyway.
     *
     * Separate from [recordMissedRelevant] because the two answer different
     * questions. That one compares the preamble against a fresh relevance
     * search and fires when retrieval MISSED something it should have picked.
     * This one compares a human's own words against what the model was given and
     * fires when the model HAD the memory and failed to use it. Both are recall
     * failures, and only this one can be decided from the ledger alone.
     *
     * The candidate bodies are not stored in the ledger (it holds ids and query
     * text, never memory bodies), so the caller supplies them.
     */
    suspend fun recordRestatements(
        conversationId: String,
        userMessage: String,
        candidates: List<InjectedMemory>,
        injectedThisTurn: Set<String>,
    ) {
        if (candidates.isEmpty() || userMessage.isBlank()) return
        runCatching {
            val restated = RecallSignals.restatementsFor(
                userMessage = userMessage,
                candidates = candidates,
                alreadyInjectedIds = injectedThisTurn,
            )
            for (candidate in restated) {
                recordRecallFailure(
                    query = userMessage,
                    conversationId = conversationId,
                    memoryId = candidate.memoryId,
                    score = candidate.score,
                )
            }
        }.onFailure {
            logWarn("recordRestatements failed: ${it.message}")
        }
    }

    /**
     * The memories this conversation injected before now, as candidates for a
     * restatement check.
     *
     * Only ids, lanes, ranks and scores survive the read, because that is all the
     * ledger stores. `content` comes back blank and the caller fills it from the
     * brain, which keeps memory bodies out of the ledger while still letting the
     * comparison run.
     */
    suspend fun recentCandidates(conversationId: String, limit: Int = 40): List<InjectedMemory> =
        runCatching {
            injectionDao.relevantForConversation(conversationId, limit).map { row ->
                InjectedMemory(
                    memoryId = row.memoryId,
                    lane = row.lane,
                    rankInLane = row.rankInLane,
                    score = row.score,
                    content = "",
                )
            }
        }.getOrDefault(emptyList())

    /** Lane precision over a window, for comparing retrieval lanes on real data. */
    suspend fun precisionByLane(sinceMillis: Long) =
        runCatching { injectionDao.precisionByLane(sinceMillis) }.getOrDefault(emptyList())

    /** Recall failures in a window: restatements the preamble should have caught. */
    suspend fun recallFailuresSince(sinceMillis: Long): Int =
        runCatching { injectionDao.recallFailuresSince(sinceMillis) }.getOrDefault(0)

    companion object {
        /** Lane name for a recall failure, matching the entity's lane vocabulary. */
        const val LANE_RESTATED = "restated"

        /**
         * Ceiling on conversations held between the preamble and the reply.
         *
         * A reply normally follows its preamble within the same turn, so this
         * only ever fills when turns are abandoned. Small on purpose: the held
         * list is a copy of one turn's injections and nothing of lasting value
         * lives here.
         */
        private const val MAX_PENDING_TURNS = 8
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
