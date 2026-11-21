// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * Whether one agent may hand work to another, and what the child is allowed to do with it.
 *
 * WHY THIS IS PURE AND SEPARATE. Delegation is where an ecosystem of agents becomes dangerous: a
 * child that inherits its parent's permissions widens authority by accident, and a chain of agents
 * delegating to each other is a recursion nobody asked for. Both of those are decisions about
 * values, not about Android, so they live here where a JVM test can exhaust them in milliseconds and
 * where the answer cannot drift between the two execution paths.
 *
 * THE FOUR RULES, EACH OF WHICH IS A REAL WAY THIS GOES WRONG.
 *
 *  1. A CHILD NEVER GETS MORE THAN ITS PARENT. Its tool set is the intersection, never the union,
 *     so delegating cannot be a route to a capability the parent was refused.
 *  2. DEPTH IS BOUNDED. Each run carries a depth, the child's is the parent's plus one, and a chain
 *     that reaches the ceiling is refused with the reason rather than allowed to run and fail later.
 *  3. THE PARENT MUST WANT IT. `AgentAutonomy.requestDelegation` is off by default, so an agent
 *     cannot decide to spawn helpers unless its owner said it could.
 *  4. THE TARGET MUST EXIST AND BE ENABLED. A delegation to a switched-off or deleted agent is not
 *     a delegation; it is a request that will silently produce nothing.
 *
 * WHAT THIS DOES NOT DECIDE. Whether the delegated work is a good idea, which agent is best suited,
 * and what context is worth sharing. Those are GURU's judgement, and a policy that pretended to rank
 * agents would be replacing a mind with a rule.
 */
object AgentDelegationPolicy {

    /**
     * The system ceiling on how deep a delegation chain may go.
     *
     * FIVE, BECAUSE A CHAIN IS NOT A TREE. Each level is a full model conversation costing real
     * money and, on a phone, real battery. Five is deep enough for a specialist to hand a sub-task
     * to a narrower specialist and still return something useful, and shallow enough that a runaway
     * loop costs pennies rather than pounds. It is a constant rather than a stored setting because
     * exceeding it is never a legitimate thing to want; an agent's own budget may tighten it further
     * but cannot raise it.
     */
    const val SYSTEM_MAX_DEPTH = 5

    /**
     * How many children one run may create.
     *
     * A FAN-OUT CEILING, because depth alone does not bound work: an agent at depth one could spawn
     * a hundred siblings. Four is enough to split a job into parallel specialisms without turning
     * one request into an unbounded fleet.
     */
    const val SYSTEM_MAX_CHILDREN_PER_RUN = 4

    /** What the policy was asked about, resolved from the real rows by the caller. */
    data class Request(
        /** The run asking to delegate. */
        val parentRunId: String,
        /**
         * The AGENT that run belongs to.
         *
         * SEPARATE FROM [parentRunId] BECAUSE THE SELF-DELEGATION CHECK NEEDS THE AGENT. Comparing a
         * target agent id against a parent run id would never match, so the check would pass every
         * time and an agent could delegate to itself unnoticed. That mistake was in the first draft
         * of this type and is the reason the two identities are named distinctly rather than both
         * called "parent".
         */
        val parentAgentId: String,
        /** Its depth in the chain; a top-level run is depth zero. */
        val parentDepth: Int,
        /** How many children the parent has already created. */
        val parentChildrenSoFar: Int,
        /** The parent agent's own declared tool set. */
        val parentTools: Set<String>,
        /** Whether the parent agent's autonomy permits delegation at all. */
        val parentMayDelegate: Boolean,
        /** The parent's own per-run budget for delegations, when it set one. */
        val parentMaxDelegations: Int?,
        /** The parent's own depth ceiling, when it set one. */
        val parentMaxDepth: Int?,
        /** The agent being asked to take the work, resolved from the registry. */
        val targetAgentId: String,
        val targetExists: Boolean,
        val targetEnabled: Boolean,
        /** The target agent's declared tool set. */
        val targetTools: Set<String>,
        /** Tools the parent explicitly permits the child to use. Null means "whatever the target has". */
        val requestedTools: Set<String>? = null,
    ) {
        /** The depth this delegation would produce. */
        val resultingDepth: Int get() = parentDepth + 1

        /** The deepest this chain may go, the tighter of the system ceiling and the parent's own. */
        val depthCeiling: Int
            get() = parentMaxDepth?.coerceAtMost(SYSTEM_MAX_DEPTH) ?: SYSTEM_MAX_DEPTH

        /** The most children this parent may create, the tighter of the system ceiling and its own. */
        val childCeiling: Int
            get() = parentMaxDelegations?.coerceAtMost(SYSTEM_MAX_CHILDREN_PER_RUN)
                ?: SYSTEM_MAX_CHILDREN_PER_RUN
    }

    /**
     * The verdict.
     *
     * A REFUSAL IS A REASON, NOT A BOOLEAN. The caller has to tell GURU why it cannot delegate, and
     * "no" without a cause gives the model nothing to work with. A permitted delegation carries the
     * child's tool set and depth, resolved here so the child's run row records them rather than
     * recomputing them later from a parent that may since have been deleted.
     */
    sealed interface Verdict {
        data class Permitted(
            /** Exactly the tools the child may use, already intersected with the parent's. */
            val grantedTools: Set<String>,
            /** The child's depth in the chain. */
            val depth: Int,
            /** The parent run, so the child row can name its origin. */
            val parentRunId: String,
        ) : Verdict

        data class Refused(val reason: String) : Verdict
    }

    /**
     * Decide.
     *
     * THE ORDER OF THE CHECKS IS THE ORDER A PERSON WOULD ASK THEM. Is the target real, did the
     * parent's owner allow this, has the chain gone too deep, has this run already split too far,
     * and only then what may the child actually hold. Each refusal names its own cause so the model
     * can report something true rather than a generic failure.
     */
    fun evaluate(request: Request): Verdict {
        if (!request.parentMayDelegate) {
            return Verdict.Refused(
                "This agent's autonomy does not permit delegation. Its owner can enable it in the " +
                    "agent's settings.",
            )
        }

        if (!request.targetExists) {
            return Verdict.Refused(
                "No agent called '${request.targetAgentId}' exists, so there is nobody to delegate to.",
            )
        }

        if (!request.targetEnabled) {
            return Verdict.Refused(
                "'${request.targetAgentId}' is switched off, so delegating to it would produce nothing."
            )
        }

        /*
         * A RUN MAY NOT DELEGATE TO ITSELF.
         *
         * The check is on the agent identity, not the run id, because the dangerous shape is an
         * agent handing work to its own definition: it produces two runs that share one prompt and
         * one tool set and can each delegate again, which is an unbounded fan-out wearing a chain's
         * clothes. A genuinely different agent is required.
         */
        if (request.targetAgentId == request.parentAgentId) {
            return Verdict.Refused(
                "An agent cannot delegate to itself. That would recurse with no new capability at " +
                    "each level.",
            )
        }

        if (request.resultingDepth > request.depthCeiling) {
            return Verdict.Refused(
                "This delegation would be ${request.resultingDepth} levels deep and the ceiling is " +
                    "${request.depthCeiling}. The work needs to be done at this level instead.",
            )
        }

        if (request.parentChildrenSoFar >= request.childCeiling) {
            return Verdict.Refused(
                "This run has already delegated to ${request.parentChildrenSoFar} agents and the " +
                    "limit is ${request.childCeiling}.",
            )
        }

        /*
         * THE INTERSECTION, WHICH IS THE SECURITY RULE.
         *
         * A child holds what its parent holds, narrowed by what the parent chose to hand over and by
         * what the child already declares. Not the union. Delegation must never be a route to a
         * capability the parent was refused, or a parent could acquire any tool in the app by
         * borrowing an agent that has it.
         *
         * An empty result is a real answer: a child may be delegated a job that needs no tools, and
         * it will reason and report without touching anything, exactly as any other toolless agent.
         */
        val parentAllowed = request.parentTools
        val targetDeclared = request.targetTools
        val requested = request.requestedTools

        val granted = when {
            requested == null -> parentAllowed intersect targetDeclared
            else -> (parentAllowed intersect targetDeclared) intersect requested
        }

        /*
         * A REQUESTED TOOL THE PARENT DOES NOT HOLD IS REFUSED RATHER THAN DROPPED.
         *
         * Silently narrowing would let a parent believe it delegated a capability it does not have,
         * and the child would then fail at the moment of use with a refusal that names the wrong
         * component. Saying so now lets the parent either do the work itself or ask its owner.
         */
        if (requested != null) {
            val missing = requested - parentAllowed
            if (missing.isNotEmpty()) {
                return Verdict.Refused(
                    "This agent asked to delegate ${missing.sorted().joinToString(", ")}, which it " +
                        "does not hold itself. Delegation grants a subset of the parent's tools, " +
                        "never more.",
                )
            }
        }

        return Verdict.Permitted(
            grantedTools = granted,
            depth = request.resultingDepth,
            parentRunId = request.parentRunId,
        )
    }

    /**
     * How many runs may execute at once.
     *
     * SEQUENTIAL TODAY, AND IT SAYS SO. The data model is concurrency-capable: runs have their own
     * ids, events bind to runs and each run has its own lease. What does not exist is a second
     * cognitive loop, because one phone serving one model connection cannot honestly drive two at
     * once without both becoming slower and the recovery scan unable to tell them apart.
     *
     * A CONFIGURABLE POLICY RATHER THAN A SCHEMA ASSUMPTION. Raising it later is this value, not a
     * migration, which is exactly what the specification asks for.
     */
    const val MAX_CONCURRENT_RUNS = 1

    /** Is there room for one more run, given how many are executing? */
    fun admitsAnother(activeRunCount: Int): Boolean = activeRunCount < MAX_CONCURRENT_RUNS
}
