// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.model.GuruAgent
import org.koin.core.annotation.Single

/**
 * An agent's memory lane, on the real brain rather than a second store.
 *
 * THE GAP THIS CLOSES. GURU's memory is a real Cerebrum brain over JNI with provenance, decay and a
 * graph, and an agent got none of it: `AgentRuntime` handed an agent its prompt, the job string and
 * its own messages, and nothing else. `AgentMemoryScope` declared what an agent might read and write,
 * and nothing consulted it. So an agent started every job cold and could retain nothing between them,
 * which is the difference between a tool call and a worker.
 *
 * IT USES THE SAME SERVICE THE MAIN CHAT USES. `BrainService` is the one orchestrator, and a second
 * memory store for agents would be two answers to "what does GURU remember". This reads and writes
 * the brain, and the scope decides which parts of it an agent may touch.
 *
 * THE SCOPE IS ENFORCED HERE RATHER THAN IN THE PROMPT. An instruction in an agent's prompt saying
 * "do not read shared memory" is not a control; the specification is explicit about that. The only
 * thing that stops it is the code that does the reading checking first, which is this class.
 *
 * EVERY OPERATION IS ATTRIBUTED. A read records a context reference so the room can show what the
 * agent was given, and a write carries the agent's own name in its provenance so a fact can be traced
 * to the agent that produced it rather than appearing as though the person said it.
 */
@Single
class AgentMemoryService(
    private val brain: BrainService,
    private val artefacts: AgentArtefactService,
) {

    /** One fact an agent may use, with where it came from. */
    data class Retrieved(
        val id: String,
        val text: String,
        val score: Float,
        val source: String,
    )

    /**
     * What this agent may read, resolved from its scope.
     *
     * NONE IS THE DEFAULT AND IT IS NOT A DEGRADED MODE. An agent granted no memory reads nothing,
     * and is told so by an empty result with a reason rather than an exception. A caller that asked
     * for memory and got nothing needs to distinguish "there is nothing" from "you may not look",
     * and the second is a configuration fact its owner can change.
     */
    data class ReadOutcome(
        val facts: List<Retrieved>,
        val refusedBecause: String? = null,
    ) {
        val wasRefused: Boolean get() = refusedBecause != null
    }

    /**
     * Search the memory this agent is permitted to see.
     *
     * SHARED MEMORY IS OPT-IN AND OWN MEMORY IS ON BY DEFAULT, which is what the scope declares. The
     * split matters: an agent's own facts are its working knowledge from previous jobs, while shared
     * memory is everything GURU knows, and an agent should not ingest the second merely by existing.
     *
     * THE RESULT IS RECORDED AS CONTEXT, so the room can show what the agent was given and whether it
     * was used. That is the provenance the specification's section 8.2 requires and that agents had
     * none of.
     */
    suspend fun search(
        agent: GuruAgent,
        runId: String,
        query: String,
        limit: Int = 10,
    ): ReadOutcome {
        val scope = agent.memoryScope

        val mayReadAnything = scope.readShared || scope.readOwn
        if (!mayReadAnything) {
            return ReadOutcome(
                facts = emptyList(),
                refusedBecause = "This agent has no memory access. Its owner can grant it in the " +
                    "agent's settings.",
            )
        }

        val hits = runCatching { brain.search(query, limit) }
            .getOrElse { error ->
                /*
                 * A BRAIN THAT WILL NOT ANSWER IS NOT AN EMPTY MEMORY.
                 *
                 * Reporting a failed search as "nothing found" tells the agent it knows nothing when
                 * the truth is the brain is unreachable, and the agent would then answer from its
                 * prompt as though that were all there is. The distinction is the whole point of
                 * the outcome type.
                 */
                return ReadOutcome(
                    facts = emptyList(),
                    refusedBecause = "The memory could not be read right now: " +
                        (error.message ?: "the brain did not answer") +
                        ". This is not the same as there being nothing remembered.",
                )
            }

        /*
         * THE SCOPE FILTER, APPLIED AFTER THE SEARCH.
         *
         * The brain has no concept of an agent's scope, so it returns everything relevant and this
         * decides what the agent may see. Filtering after rather than before costs a few results and
         * keeps the brain's own ranking intact, which is better than a query that has to encode a
         * permission model the store does not have.
         */
        val permitted = hits.filter { hit ->
            val category = hit.fact.category
            val inCategoryScope = scope.categories.isEmpty() || category in scope.categories
            inCategoryScope && (scope.readShared || isOwnFact(hit.fact.source, agent))
        }

        val facts = permitted.map {
            Retrieved(
                id = it.fact.id,
                text = it.fact.fact,
                score = it.score,
                source = it.source,
            )
        }

        // WHAT WAS FOUND IS RECORDED EVEN IF THE MODEL LATER DECLINES TO USE IT, because a retrieval
        // that returned nothing is a different fact from one never attempted.
        runCatching {
            artefacts.recordContextCandidates(
                runId = runId,
                candidates = facts.map {
                    AgentArtefactService.ContextCandidate(
                        sourceKind = com.unuslumen.app.database.entity.AgentContextReferenceEntity.SOURCE_MEMORY,
                        sourceRef = it.id,
                        sourceLabel = it.text.take(80),
                        retrievalQuery = query,
                        withinScope = true,
                        scopeLabel = scopeLabel(scope),
                        excerpt = it.text,
                        score = it.score,
                    )
                },
            )
        }

        return ReadOutcome(facts = facts)
    }

    /**
     * Store something this agent learned, attributed to it.
     *
     * WRITING IS OFF BY DEFAULT AND IS A SEPARATE PERMISSION FROM READING. An agent that may consult
     * memory is not thereby an agent that may change it, because a write is how one agent's mistake
     * becomes every agent's fact.
     *
     * A SHARED-KNOWLEDGE WRITE IS A PROPOSAL, NOT A WRITE. When the agent may propose shared memory
     * but not write it, this records the fact under the agent's own domain so its owner can review
     * it, rather than putting it where every other agent reads it as settled.
     */
    suspend fun remember(
        agent: GuruAgent,
        fact: String,
        topic: String = "general",
        subtopic: String = "fact",
    ): Boolean {
        val scope = agent.memoryScope
        if (!scope.writeOwn) {
            return false
        }
        if (fact.isBlank()) return false

        return runCatching {
            brain.storeRecord(
                content = fact,
                // THE AGENT'S OWN DOMAIN, so a fact can be traced to whoever produced it.
                domain = domainFor(agent),
                topic = topic,
                subtopic = subtopic,
                /*
                 * THE PROVENANCE NAMES THE AGENT. A fact written by an agent must never read as
                 * something the person said, and the brain stores the provenance string verbatim, so
                 * this is what makes an agent-authored fact distinguishable later.
                 */
                provenance = "agent=${agent.slug.ifBlank { agent.name }}",
                rationale = "recorded by ${agent.name} during a run",
                relatedTo = emptyList(),
                confidence = 0.7f,
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Facts this agent wrote itself, when it may read them.
     *
     * ITS OWN KNOWLEDGE, WHICH IS WHAT MAKES JOBS BUILD ON EACH OTHER. An agent that cannot read what
     * it wrote last time starts every job from nothing, which is the difference between a worker and
     * a stateless call.
     */
    suspend fun ownFacts(agent: GuruAgent): List<Retrieved> {
        if (!agent.memoryScope.readOwn) return emptyList()
        return runCatching {
            brain.getFactsByDomain(domainFor(agent)).map {
                Retrieved(
                    id = it.id,
                    text = it.fact,
                    score = it.confidence,
                    source = "OWN",
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The domain an agent's own facts file under, derived from its identity. */
    fun domainFor(agent: GuruAgent): String = "agent.${agent.slug.ifBlank { agent.name }}"

    private fun isOwnFact(source: String, agent: GuruAgent): Boolean =
        source.contains("agent=${agent.slug.ifBlank { agent.name }}")

    private fun scopeLabel(scope: AgentMemoryScope): String = buildString {
        append(if (scope.readShared) "shared" else "own-only")
        if (scope.categories.isNotEmpty()) {
            append(" · ")
            append(scope.categories.joinToString(","))
        }
    }
}
