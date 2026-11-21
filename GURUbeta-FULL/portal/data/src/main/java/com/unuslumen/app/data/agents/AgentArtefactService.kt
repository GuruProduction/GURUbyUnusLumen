// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.database.dao.AgentArtefactDao
import com.unuslumen.app.database.dao.AgentContextReferenceDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.entity.AgentArtefactEntity
import com.unuslumen.app.database.entity.AgentContextReferenceEntity
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Records what a run produced, and what it was given.
 *
 * TWO JOBS IN ONE PLACE BECAUSE THEY ANSWER ONE QUESTION. "Why does this exist" is answered by the
 * artefact (what it is, which run, from which agent) plus the context references (what the agent was
 * reading). Splitting them into two services would mean two callers each remembering to record their
 * half, and the half that gets forgotten is always provenance.
 *
 * THE ARTEFACT POINTS AT CONTENT, IT DOES NOT EAT IT. A project document, a media item or a file is
 * named by [AgentArtefactEntity.STORAGE_*] plus a ref, and only a small output is carried inline.
 * The write refuses an oversized inline body rather than truncating it, because a truncated report
 * reads as a complete one.
 *
 * THE CONTEXT RECORD IS SPLIT BY [suppliedToModel] ON PURPOSE. Everything a retrieval found is
 * recorded; only what actually reached the model is marked supplied. That difference is what stops
 * a provenance panel claiming the agent read something it never saw.
 */
@OptIn(ExperimentalUuidApi::class)
@Single
class AgentArtefactService(
    private val artefactDao: AgentArtefactDao,
    private val contextDao: AgentContextReferenceDao,
    private val runDao: AgentRunDao,
    private val ledger: AgentRunLedger,
) {

    /** Too much content to carry in a row. Raised rather than stored truncated. */
    class TooLargeForInline(override val message: String) : IllegalArgumentException(message)

    /**
     * Register an output against the run that made it.
     *
     * THE RUN IS VERIFIED FIRST. An artefact whose run does not exist would be evidence of work
     * nobody can find, and the foreign key would refuse it anyway with an error that says nothing
     * about the cause. Checking here lets the refusal name the real problem.
     *
     * Returns null when the run is unknown, so a caller cannot mistake a failed record for a
     * successful one by ignoring an exception.
     */
    suspend fun recordArtefact(
        runId: String,
        title: String,
        kind: String,
        storageKind: String,
        storageRef: String,
        mimeType: String = "text/plain",
        contentText: String? = null,
        sizeBytes: Long = 0,
        sources: List<SourceRef> = emptyList(),
        finalOutput: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): AgentArtefactEntity? {
        val run = runDao.run(runId) ?: return null

        val inline = when (storageKind) {
            AgentArtefactEntity.STORAGE_INLINE -> {
                val body = contentText
                    ?: throw IllegalArgumentException(
                        "An inline artefact needs its content. Use a store kind for anything held elsewhere."
                    )
                val bytes = body.toByteArray(Charsets.UTF_8).size
                if (bytes > AgentArtefactEntity.MAX_INLINE_BYTES) {
                    /*
                     * REFUSED, NOT TRUNCATED. Storing the first 16 KiB of a 200 KiB report would
                     * produce an artefact that looks complete and is not, which is worse than an
                     * error the caller has to handle. The message names the actual sizes so the
                     * caller can decide to use a store.
                     */
                    throw TooLargeForInline(
                        "That output is $bytes bytes and an inline artefact is limited to " +
                            "${AgentArtefactEntity.MAX_INLINE_BYTES}. Store it as a file or a " +
                            "project document and record the reference instead."
                    )
                }
                body
            }
            else -> contentText?.takeIf { it.isNotBlank() }
        }

        val artefact = AgentArtefactEntity(
            artefactId = Uuid.random().toString(),
            runId = runId,
            agentId = run.agentId,
            agentName = run.agentName,
            agentVersion = run.agentVersion,
            title = title,
            kind = kind,
            mimeType = mimeType,
            storageKind = storageKind,
            storageRef = storageRef,
            contentText = inline,
            sizeBytes = if (sizeBytes > 0) sizeBytes else (inline?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L),
            sourcesJson = encodeSources(sources),
            finalOutput = finalOutput,
            createdAt = now,
        )
        artefactDao.insert(artefact)

        // THE LEDGER SAYS ONE WAS MADE. The artefact table holds the provenance; the ledger records
        // that the run produced something at this point in its history, so a replay of events shows
        // the output appearing where it did rather than only that it exists afterwards.
        runCatching {
            ledger.append(
                runId,
                AgentRunLedger.EventDraft(
                    eventType = com.unuslumen.app.domain.agents.AgentEventTypes.ARTEFACT_CREATED,
                    actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_AGENT,
                    actorId = run.agentId,
                    payload = buildJson {
                        put("artefactId", artefact.artefactId)
                        put("artefactKind", kind)
                        put("title", title)
                        put("storageKind", storageKind)
                    },
                )
            )
        }

        return artefact
    }

    /** Everything one run produced, in creation order. */
    suspend fun artefactsFor(runId: String): List<AgentArtefactEntity> = artefactDao.forRun(runId)

    /** Everything one agent has ever made, newest first. */
    suspend fun artefactsBy(agentId: String): List<AgentArtefactEntity> = artefactDao.forAgent(agentId)

    suspend fun artefact(artefactId: String): AgentArtefactEntity? = artefactDao.artefact(artefactId)

    // ------------------------------------------------------------------
    // Context provenance
    // ------------------------------------------------------------------

    /** One candidate piece of information, before it is known whether it reached the model. */
    data class ContextCandidate(
        val sourceKind: String,
        val sourceRef: String,
        val sourceLabel: String = "",
        val retrievalQuery: String = "",
        val withinScope: Boolean = true,
        val scopeLabel: String = "",
        val excerpt: String = "",
        val rankInLane: Int = 0,
        val score: Float = 0f,
    )

    /**
     * Record what a retrieval offered the agent, whether or not it fitted.
     *
     * THE WHOLE CANDIDATE LIST IS WRITTEN, NOT JUST WHAT WAS USED. A retrieval that found twelve
     * things and delivered two is a fact worth having: it explains a thin answer without blaming
     * the model, and it is invisible if only the two are recorded.
     *
     * Returns the rows written, so the caller can mark which ones it actually supplied.
     */
    suspend fun recordContextCandidates(
        runId: String,
        candidates: List<ContextCandidate>,
        now: Long = System.currentTimeMillis(),
    ): List<AgentContextReferenceEntity> {
        if (candidates.isEmpty()) return emptyList()
        if (runDao.run(runId) == null) return emptyList()

        val rows = candidates.map { candidate ->
            AgentContextReferenceEntity(
                referenceId = Uuid.random().toString(),
                runId = runId,
                sourceKind = candidate.sourceKind,
                sourceRef = candidate.sourceRef,
                sourceLabel = candidate.sourceLabel,
                retrievalQuery = candidate.retrievalQuery,
                withinScope = candidate.withinScope,
                scopeLabel = candidate.scopeLabel,
                // NOT YET. The caller marks this once it knows what fitted.
                suppliedToModel = false,
                excerpt = candidate.excerpt.take(AgentContextReferenceEntity.MAX_EXCERPT_CHARS),
                rankInLane = candidate.rankInLane,
                score = candidate.score,
                createdAt = now,
            )
        }
        contextDao.insertAll(rows)
        return rows
    }

    /**
     * Mark candidates as having actually reached the model.
     *
     * CALLED AFTER THE PROMPT IS BUILT, NOT WHEN THE CANDIDATE IS FOUND. The gap between those two
     * moments is exactly the case this models: a retrieval that succeeded and a context window that
     * did not have room.
     */
    suspend fun markSupplied(runId: String, referenceIds: List<String>) {
        for (id in referenceIds) {
            contextDao.markSupplied(id, runId)
        }
    }

    /** Everything considered for one run. */
    suspend fun contextFor(runId: String): List<AgentContextReferenceEntity> = contextDao.forRun(runId)

    /** Only what actually reached the model. */
    suspend fun suppliedContextFor(runId: String): List<AgentContextReferenceEntity> =
        contextDao.suppliedToModel(runId)

    /** Where one memory or document was cited. */
    suspend fun citationsOf(sourceKind: String, sourceRef: String): List<AgentContextReferenceEntity> =
        contextDao.citationsOf(sourceKind, sourceRef)

    // ------------------------------------------------------------------

    /** One typed pointer from an artefact to what produced it. */
    data class SourceRef(val kind: String, val id: String, val label: String = "")

    private fun encodeSources(sources: List<SourceRef>): String {
        if (sources.isEmpty()) return "[]"
        return sources.joinToString(",", prefix = "[", postfix = "]") { source ->
            buildJson {
                put("kind", source.kind)
                put("id", source.id)
                put("label", source.label)
            }
        }
    }

    private fun buildJson(block: JsonScope.() -> Unit): String {
        val map = linkedMapOf<String, Any?>()
        JsonScope(map).block()
        return buildString {
            append('{')
            var first = true
            for ((key, value) in map) {
                if (!first) append(',')
                first = false
                append('"').append(key).append("\":")
                when (value) {
                    null -> append("null")
                    is Number, is Boolean -> append(value.toString())
                    else -> append('"').append(escape(value.toString())).append('"')
                }
            }
            append('}')
        }
    }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(String.format("%04x", c.code)) else append(c)
            }
        }
    }

    private class JsonScope(private val map: MutableMap<String, Any?>) {
        fun put(key: String, value: String?) { map[key] = value }
        fun put(key: String, value: Int) { map[key] = value }
        fun put(key: String, value: Long) { map[key] = value }
        fun put(key: String, value: Boolean) { map[key] = value }
    }
}
