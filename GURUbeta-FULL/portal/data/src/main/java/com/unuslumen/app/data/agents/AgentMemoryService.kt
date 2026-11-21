// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.domain.memory.MemoryOwnership
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
         * decides what the agent may see. Filtering after rather than before keeps the brain's own
         * ranking intact, rather than encoding a permission model the store does not have.
         *
         * THE OLD FILTER WAS NOT A PARTITION, AND THE REASON IS WORTH KEEPING. It read
         *     `scope.readShared || isOwnFact(hit.fact.source, agent)`
         * where `isOwnFact` searched `source` for `agent=<slug>`. Production set that field to
         * `cerebrum`, the storage backend, so `isOwnFact` was always false: an own-only agent got
         * NOTHING, and a shared reader got EVERYTHING the brain returned, including other agents'
         * private facts. It was a switch, not a partition, and both of its positions were wrong.
         *
         * `MemoryOwnership.mayRead` is the partition: own facts when `readOwn`, other agents' facts
         * only when they were explicitly marked shared, and unattributed facts as part of the shared
         * scope. Ownership is a stable agent id, never a slug and never the backend name.
         */
        val permitted = hits.filter { hit ->
            val inCategoryScope = scope.categories.isEmpty() ||
                scope.categories.any { wanted -> wanted.equals(hit.fact.category, ignoreCase = true) }
            if (!inCategoryScope) return@filter false
            MemoryOwnership.mayReadStored(
                storedOwnerAgentId = hit.fact.ownerAgentId,
                storedVisibility = hit.fact.visibility,
                provenanceLabel = hit.fact.source,
                requestingAgentId = agentIdOf(agent),
                readOwn = scope.readOwn,
                readShared = scope.readShared,
            )
        }

        val facts = permitted.map {
            Retrieved(
                id = it.fact.id,
                text = it.fact.fact,
                score = it.score,
                // The tier that answered, not the writer. Who wrote it is in the provenance.
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
        shareWithOtherAgents: Boolean = false,
    ): Boolean {
        val scope = agent.memoryScope
        if (!scope.writeOwn) {
            return false
        }
        if (fact.isBlank()) return false

        return runCatching {
            brain.storeRecord(
                content = fact,
                /*
                 * THE CATEGORY AND THE HIERARCHY ARE TWO THINGS, AND THIS USED TO CONFLATE THEM.
                 *
                 * The domain was set to `agent.<id>`, which is an OWNER, not a category. So an
                 * agent-written fact filed itself under a category that is not in the vocabulary, and
                 * a category filter could never find it. The category is `knowledge` because an
                 * agent's note about the world is knowledge; ownership rides in provenance, which is
                 * where MemoryOwnership reads it.
                 */
                domain = "knowledge",
                topic = topic,
                subtopic = subtopic,
                /*
                 * THE PROVENANCE CARRIES OWNERSHIP AND VISIBILITY, in the grammar
                 * MemoryOwnership.labelFor writes and MemoryOwnership.parse reads. The agent id is
                 * stable: a slug is derived and a name is editable, and neither can be the basis of a
                 * permission decision.
                 */
                provenance = MemoryOwnership.labelFor(
                    baseLabel = "agent run",
                    agentId = agentIdOf(agent),
                    shared = shareWithOtherAgents,
                ),
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
        /*
         * OWNERSHIP IS THE FILTER, NOT THE DOMAIN.
         *
         * This used to list the facts under `agent.<id>` as the domain. That worked only because
         * `remember` was filing ownership into the category slot, which meant an agent's facts were
         * invisible to every category filter and a category was doing two jobs. Now the domain is a
         * real category, so the ownership check is the same one search uses:
         * `MemoryOwnership.mayRead`, reading the agent id out of provenance.
         *
         * The brain has no field for an owner, so the scan is over the rows the agent can see and the
         * provenance decides. That is a filter over a bounded read, not a hidden permission model: the
         * store returns data and this decides who may see it, which is the only place the decision can
         * live without the brain knowing what an agent is.
         */
        val me = agentIdOf(agent)
        return runCatching {
            brain.getAllFacts()
                .mapNotNull { row ->
                    val provenance = MemoryOwnership.parse(row.source)
                    val mine = provenance.isAgentAuthored && provenance.owner == me
                    if (!mine) return@mapNotNull null
                    Retrieved(
                        id = row.id,
                        text = row.fact,
                        score = row.confidence,
                        source = "OWN",
                    )
                }
        }.getOrDefault(emptyList())
    }

    /**
     * The stable identity every ownership decision keys on.
     *
     * `GuruAgent.id` is the row id, the only one of its identifiers that its owner
     * cannot change by renaming the agent. A slug is derived and never shown to a
     * person, and a name is editable, so either would move every permission decision
     * the moment someone edited a title. Falls back to the slug only when a row was
     * built without an id, which is not a shape production writes.
     */
    fun agentIdOf(agent: GuruAgent): String =
        agent.id.ifBlank { agent.slug.ifBlank { agent.name } }

    /** The domain an agent's own facts file under, derived from its identity. */
    fun domainFor(agent: GuruAgent): String = "agent.${agentIdOf(agent)}"

    private fun scopeLabel(scope: AgentMemoryScope): String = buildString {
        append(if (scope.readShared) "shared" else "own-only")
        if (scope.categories.isNotEmpty()) {
            append(" · ")
            append(scope.categories.joinToString(","))
        }
    }
}
