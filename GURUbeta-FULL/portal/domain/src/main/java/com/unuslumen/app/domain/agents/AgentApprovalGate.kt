// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * Whether a tool call must stop and ask before it runs.
 *
 * THE GAP THIS CLOSES. An approval could be requested, stored, decided and recorded, and no tool call
 * ever consulted any of it. That is the difference between an approval system existing and an
 * approval system being in the way, and only the second one stops an agent doing something its owner
 * wanted to be asked about.
 *
 * THREE OUTCOMES, BECAUSE THERE ARE THREE REAL CASES. Run it, because nothing about it needs a yes.
 * Ask first, because it matches an approval trigger the owner set. Refuse it, because an approval was
 * already requested for this exact action and has not been granted: a second request for the same
 * thing would be the agent asking the same question twice, which is a failure to remember, not a new
 * decision.
 *
 * THE MATCHING IS ON TOOL NAMES AND ON THE TRIGGER KIND, NEVER ON PROSE. An approval trigger is a
 * declared category; the tool is a declared name. Comparing them means the gate cannot be talked
 * around by phrasing, which an instruction in a prompt always can.
 */
object AgentApprovalGate {

    /** What the gate was asked about. */
    data class Request(
        /** The tool the agent is about to call. */
        val toolName: String,
        /** The approval triggers this agent's autonomy requires. */
        val requiredFor: Set<com.unuslumen.app.domain.model.ApprovalTrigger>,
        /** Tools the agent may use without asking, as resolved by its autonomy. */
        val useToolsWithoutAsking: Boolean,
        /**
         * True when an approval for this exact tool was already granted and is still unused.
         *
         * A GRANT IS CONSUMED BY THE CALL IT AUTHORISED. Without that, one yes would authorise every
         * future send of the same kind, and the owner would have said yes once for an unbounded
         * number of actions.
         */
        val alreadyGranted: Boolean,
        /** True when a request for this tool is outstanding and undecided. */
        val alreadyPending: Boolean,
        /**
         * Tools that always need a yes regardless of configuration.
         *
         * THE FLOOR UNDER THE POLICY. Certain capabilities reach outside the phone in a way that is
         * not recoverable, and a default that let them run without a yes would be wrong whatever the
         * agent's settings said. This is the one place the gate overrides the agent's own choice.
         */
        val alwaysAsk: Set<String> = DEFAULT_ALWAYS_ASK,
    )

    /**
     * The tools that ask by default.
     *
     * DELIBERATELY SHORT AND DELIBERATELY ABOUT IRREVERSIBILITY. Sending a message, spending money
     * and publishing a public artefact cannot be taken back. Reading a file or taking a screenshot
     * can be. An agent that had to ask for everything would be useless; one that never asked for the
     * three things it cannot undo would be dangerous.
     */
    val DEFAULT_ALWAYS_ASK: Set<String> = setOf(
        "sendSms",
        "phoneCall",
        "publishAgent",
        "deleteAgent",
    )

    enum class Verdict {
        /** Nothing about this call needs a yes. */
        PROCEED,

        /**
         * Stop and ask.
         *
         * The reason is carried so the request that follows can populate its "why" honestly instead
         * of a generic sentence.
         */
        ASK,

        /**
         * There is already an outstanding or granted decision for this action.
         *
         * A pending one means the agent must wait rather than ask again; a granted one is consumed by
         * the call, so this is only reached when the grant has already been spent.
         */
        BLOCKED,
    }

    data class Decision(
        val verdict: Verdict,
        val reason: String,
        /** Which configured trigger demanded the yes, when one did. */
        val matchedTrigger: com.unuslumen.app.domain.model.ApprovalTrigger? = null,
    )

    /**
     * Decide whether this call may run.
     *
     * THE ORDER IS THE ORDER THAT MATTERS. An already-granted approval is honoured first, because a
     * person has answered. Then the always-ask floor, which no configuration can lower. Then the
     * agent's own declared triggers. Then the autonomy's blanket permission.
     *
     * THE BLANKET PERMISSION COMES LAST AND ONLY APPLIES IF NOTHING ELSE FIRED. `useToolsWithoutAsking`
     * is the agent's general setting; it cannot erase a specific trigger the owner set, because a
     * specific choice always beats a general one.
     */
    fun evaluate(request: Request): Decision {
        if (request.alreadyGranted) {
            return Decision(
                verdict = Verdict.PROCEED,
                "An approval for this exact action has been granted.",
            )
        }

        if (request.alreadyPending) {
            return Decision(
                verdict = Verdict.BLOCKED,
                "An approval for this action is already waiting on a decision, so asking again " +
                    "would be the same question twice.",
            )
        }

        if (request.toolName in request.alwaysAsk) {
            return Decision(
                verdict = Verdict.ASK,
                "'${request.toolName}' cannot be undone once it runs, so it always needs a yes.",
            )
        }

        // THE AGENT'S OWN TRIGGERS, matched by the tool's own nature rather than its name.
        val matched = request.requiredFor.firstOrNull { trigger -> triggerMatches(trigger, request.toolName) }
        if (matched != null) {
            return Decision(
                verdict = Verdict.ASK,
                reason = matched.label + ", which this agent's settings require a decision for.",
                matchedTrigger = matched,
            )
        }

        if (!request.useToolsWithoutAsking) {
            return Decision(
                verdict = Verdict.ASK,
                reason = "This agent may not use its tools without asking, so every call needs a yes.",
            )
        }

        return Decision(
            verdict = Verdict.PROCEED,
            reason = "Nothing about this call needs a decision.",
        )
    }

    /**
     * Does one configured trigger apply to a tool?
     *
     * MATCHED BY CATEGORY, NOT BY A LIST OF NAMES. Enumerating every tool that "reaches outside the
     * phone" would be a list that goes stale the moment a tool is added; `EXTERNAL_SIDE_EFFECT` is the
     * category and this is where a tool's membership in it is decided.
     */
    private fun triggerMatches(
        trigger: com.unuslumen.app.domain.model.ApprovalTrigger,
        toolName: String,
    ): Boolean = when (trigger) {
        com.unuslumen.app.domain.model.ApprovalTrigger.EXTERNAL_SIDE_EFFECT ->
            toolName in DEFAULT_ALWAYS_ASK
        com.unuslumen.app.domain.model.ApprovalTrigger.DELEGATION ->
            toolName in setOf("spawnAgent", "resumeAgent")
        com.unuslumen.app.domain.model.ApprovalTrigger.ARTEFACT_PUBLICATION ->
            toolName in setOf("publishAgent", "createRelease")
        com.unuslumen.app.domain.model.ApprovalTrigger.MEMORY_WRITE ->
            toolName.startsWith("memory") || toolName.startsWith("remember")
        com.unuslumen.app.domain.model.ApprovalTrigger.TOOL_EXECUTION ->
            // Every tool, which is what "ask before running a tool" means. Combined with the
            // always-ask floor it makes an agent that asks for everything, and a person who does not
            // want that turns the trigger off rather than having the gate second-guess them.
            true
    }
}
