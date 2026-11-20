// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * What the Agent Room draws, assembled from persisted facts and nothing else.
 *
 * WHY THIS TYPE EXISTS RATHER THAN THE SCREEN READING TABLES DIRECTLY. A Compose screen that queries
 * four DAOs is a screen that cannot be tested without a device, and it will grow a fifth query and a
 * sixth until the layout logic and the data logic are one thing. This is a plain value: build it
 * from the database in one place, assert on it in a JVM test, and let the screen render it.
 *
 * EVERY FIELD HERE COMES FROM A ROW. There is no computed "progress percentage", no invented
 * activity line, and no placeholder. The specification is explicit that a dashboard derives progress
 * from recorded execution state, and the way to guarantee that is for the state type to have no
 * field that could be filled with a guess.
 *
 * THE THREE SECTIONS ARE THE THREE QUESTIONS A PERSON HAS. "Is it working, what is it doing, and
 * what has it done." Overview answers the first, the plan and tools answer the second, the
 * communications and artefacts answer the third.
 */
data class AgentRoomState(
    val agentId: String,
    val agentName: String,
    val agentSlug: String,
    val agentDescription: String,
    val agentEnabled: Boolean,

    /** The live run, when there is one. Null means this agent is idle. */
    val run: RunView?,

    /** The most recent completed or failed runs, newest first. */
    val history: List<RunSummaryView>,

    /** The eleven visibility rules, so the room can honour what it is allowed to draw. */
    val visibility: com.unuslumen.app.domain.model.AgentVisibility,

    /** The autonomy the agent operates under, shown rather than hidden. */
    val autonomy: com.unuslumen.app.domain.model.AgentAutonomy,

    /** Anything that would stop this agent running, from the validator. Empty when it can run. */
    val blockers: List<String>,

    /** Warnings that do not stop it, such as a model with no reasoning channel. */
    val warnings: List<String>,
) {
    val isRunning: Boolean get() = run != null && run.status in ACTIVE_STATUSES

    /**
     * What an idle agent shows instead of a fake activity line.
     *
     * A ROOM WITH NOTHING HAPPENING SAYS SO. A pulsing dot with no run behind it is the same defect
     * as a loading screen that names nothing.
     */
    val idleBecause: String get() = when {
        !agentEnabled -> "This agent is switched off, so nothing is running."
        history.isEmpty() -> "This agent has not run yet."
        else -> "Nothing is running right now. Its last run finished ${history.first().relativeAge()}."
    }

    companion object {
        /**
         * The statuses that mean work is genuinely in progress.
         *
         * WAITING IS IN PROGRESS. A run waiting for an answer or an approval is not idle; it is
         * blocked, which is a different thing and needs the person. Leaning it out of this set
         * would make a blocked run look finished.
         */
        val ACTIVE_STATUSES: Set<String> = setOf(
            AgentRunStatuses.CREATED,
            AgentRunStatuses.STARTING,
            AgentRunStatuses.RUNNING,
            AgentRunStatuses.WAITING_FOR_GURU,
            AgentRunStatuses.WAITING_FOR_USER,
            AgentRunStatuses.WAITING_FOR_TOOL,
            AgentRunStatuses.PAUSED,
            AgentRunStatuses.INTEGRATING,
            AgentRunStatuses.INTERRUPTED,
        )
    }
}

/** One run, as the room shows it. Assembled from the run row and its ledger. */
data class RunView(
    val runId: String,
    val assignment: String,
    val status: String,
    val startedAt: Long?,
    val finishedAt: Long?,
    val failureMessage: String?,
    val resultSummary: String?,

    /** The plan, as steps the runtime actually recorded. Empty when it recorded none. */
    val planSteps: List<PlanStepView>,

    /**
     * The rest of the plan the steps belong to, when the agent supplied any of it.
     *
     * SEPARATE FROM THE STEPS BECAUSE IT IS ABOUT THE PLAN RATHER THAN A STEP. Section 4.1 asks a
     * plan to carry objectives, dependencies, expected outputs, verification requirements and the
     * tools it expects, and none of those belong on an individual step. Null when the run recorded
     * no plan at all, which is different from a plan whose extras were left blank.
     */
    val plan: PlanView?,

    /** Tool activity, newest last. Expandable in the UI; the data is the actual calls. */
    val toolActivity: List<ToolActivityView>,

    /** The conversation, in order, with stored authorship. */
    val messages: List<MessageView>,

    /** Reasoning the model actually provided. Empty when the provider gave none, and says so. */
    val reasoning: ReasoningView,

    /**
     * What the agent deliberately recorded as its decisions, in order.
     *
     * A DIFFERENT KIND OF INFORMATION FROM THE REASONING ABOVE, AND THE SPECIFICATION IS EXPLICIT
     * THAT THE TWO MUST NEVER BE PRESENTED AS INTERCHANGEABLE. Reasoning is provider output: it exists only
     * where the model emits a reasoning channel and says nothing on a model that does not. A
     * decision is a fact the agent wrote on purpose, so it exists on every model, and it carries
     * named fields rather than prose a reader has to interpret.
     */
    val decisions: List<DecisionView>,

    /**
     * What the run recorded checking, and how far each check got.
     *
     * SECTION 4.4 ASKS FOR A COMPLETION CLAIM TO BE SUPPORTED BY EVIDENCE. A report is prose and a
     * claim in it reads the same whether or not anything was checked, so the checks are drawn as
     * their own facts beside the decisions. [VerificationView.supportsCompletion] is what tells a
     * reader whether "done" in the report is backed by the one level that means it.
     */
    val verifications: List<VerificationView>,

    /** What the run was given, and what it was only offered. */
    val contextSupplied: Int,
    val contextOffered: Int,

    /**
     * The sources themselves, so a reader can see what informed the work rather than only how many.
     *
     * SECTION 9.14 ASKS FOR THE SOURCES, NOT A COUNT. Two numbers say that eight things were found
     * and two were used; they do not say which two, and the whole point of provenance is answering
     * "why did it say that". Each entry carries what it was, whether it was in scope, and whether it
     * actually reached the model.
     */
    val contextSources: List<ContextSourceView>,

    /** What it produced. */
    val artefacts: List<ArtefactView>,

    /** Decisions waiting on the person. */
    val pendingApprovals: List<ApprovalView>,

    /** The immutable configuration this run actually used. */
    val effectiveConfigSummary: String,
) {
    val isWaitingOnUser: Boolean
        get() = status == AgentRunStatuses.WAITING_FOR_USER ||
            pendingApprovals.isNotEmpty()

    val isTerminal: Boolean
        get() = status in AgentRunStatuses.TERMINAL

    /**
     * Whether pausing this run would do anything.
     *
     * A PAUSE NEEDS A LIVE LOOP TO SET A FLAG ON, so this is true only for a run that is executing
     * right now. Everything else is already stopped and a Pause control would do nothing while
     * implying it does, which is the exact defect the specification forbids by name:
     *
     *  - the waiting states have released their lease, so nothing is driving them;
     *  - PAUSED is already paused;
     *  - INTERRUPTED is stopped because the process died, not because anyone asked, and it is
     *    carried on with resume rather than paused;
     *  - CREATED and STARTING exist for the instant before a loop is running, during which the
     *    runtime is already at the boundary a pause would ask it to stop at.
     */
    val canPause: Boolean
        get() = status == AgentRunStatuses.RUNNING

    /**
     * Whether this run can be carried on from where it stopped.
     *
     * TWO STATES, AND THEY ARE DIFFERENT. PAUSED is a person stopping work that could continue.
     * INTERRUPTED is the platform killing it, which is also continuable because the checkpoint holds
     * the state. Every other non-terminal state either resumes itself or is waiting on a person.
     */
    val canResume: Boolean
        get() = status == AgentRunStatuses.PAUSED || status == AgentRunStatuses.INTERRUPTED

    /**
     * How far through the plan the run is, as a count and never a percentage.
     *
     * A COUNT OF RECORDED STEPS. A percentage would need a denominator the runtime does not have:
     * an agent may add steps as it goes, and a bar that jumps backwards is worse than two numbers.
     */
    val planProgress: String? get() = if (planSteps.isEmpty()) null else {
        "${planSteps.count { it.state == PlanStepView.STATE_DONE }} of ${planSteps.size} steps done"
    }
}

/**
 * The plan a run recorded, beyond its steps.
 *
 * EVERY FIELD IS BLANK RATHER THAN ABSENT WHEN THE AGENT DID NOT SAY, because the room renders
 * nothing for a blank one. That is the same rule the decision record follows: an empty labelled row
 * reads as a claim that there was nothing to say, which is a different fact from not having been
 * asked, and a surface that cannot tell them apart is lying by layout.
 *
 * The revision is the run's own count, so "the third plan it made" is answerable, and the ledger
 * keeps every revision so a reader can see the approach change rather than only where it landed.
 */
data class PlanView(
    val revision: Int,
    val title: String,
    val objective: String,
    /** Pairs such as 2>3, meaning step two must finish before step three. */
    val dependencies: String,
    val expectedOutputs: String,
    val verification: String,
    val tools: String,
    /** Why this plan rather than another, especially when replacing one that failed. */
    val reason: String,
    val at: Long,
) {
    /** Whether the agent said anything about the plan beyond its steps. */
    val hasDetail: Boolean
        get() = objective.isNotBlank() || dependencies.isNotBlank() ||
            expectedOutputs.isNotBlank() || verification.isNotBlank() ||
            tools.isNotBlank() || reason.isNotBlank()
}

/** One step of a plan, as it was recorded. */
data class PlanStepView(
    val stepId: String,
    val description: String,
    val state: String,
    val updatedAt: Long,
) {
    companion object {
        const val STATE_PROPOSED = "PROPOSED"
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_DONE = "DONE"
        const val STATE_BLOCKED = "BLOCKED"
        const val STATE_ABANDONED = "ABANDONED"
    }
}

/** One tool invocation, with the actual arguments and result. */
data class ToolActivityView(
    val invocationId: String,
    val toolName: String,
    val arguments: String,
    val result: String?,
    val failed: Boolean,
    val refused: Boolean,
    val outcomeUnknown: Boolean,
    val durationMillis: Long?,
    val permissionOutcome: String?,
    val at: Long,
)

/** One message, with stored authorship. Never inferred from position. */
data class MessageView(
    val messageId: String,
    val authorType: String,
    val authorName: String,
    val recipientType: String,
    val messageType: String,
    val content: String,
    val deliveryState: String,
    val at: Long,
) {
    val isFromAgent: Boolean
        get() = authorType == AgentMessageAuthors.AGENT
}

/**
 * The reasoning the model actually provided, and whether any was expected.
 *
 * [providerSupportsReasoning] IS THE HONESTY FIELD. A model without a reasoning channel cannot be
 * made to produce one by adding a column, so the room says plainly that raw reasoning is unavailable
 * rather than drawing an empty panel that looks like a bug.
 */
data class ReasoningView(
    val text: String,
    val providerSupportsReasoning: Boolean,
    val capturedAtStart: Boolean,
) {
    val status: String
        get() = when {
            text.isNotBlank() -> "CAPTURED"
            !providerSupportsReasoning ->
                "This model does not provide a reasoning channel, so there is none to show."
            !capturedAtStart ->
                "Reasoning capture was off when this run started, so nothing was recorded."
            else -> "The model provided no reasoning for this run."
        }

    val hasAnything: Boolean get() = text.isNotBlank()
}

/**
 * One decision the agent recorded, with its reasons.
 *
 * THE FIELDS ARE THE SPECIFICATION'S, NOT A SUMMARY OF THEM. Section 4.1 and 4.2 name what a
 * decision record should carry: the problem as the agent understood it, the approach, the
 * alternatives considered, why one was chosen, the evidence, the remaining uncertainty and the
 * intended next step. A type that flattened those into one paragraph would be a different thing
 * wearing the same name.
 *
 * A BLANK OPTIONAL FIELD IS BLANK, NOT MISSING. The agent may genuinely have had nothing to say
 * about its uncertainty, and the room renders absence rather than a placeholder sentence that
 * reads like a claim.
 */
data class DecisionView(
    /** One-based within the run, so "the third thing it decided" is answerable. */
    val sequence: Int,
    val decision: String,
    val problem: String,
    val approach: String,
    val alternatives: List<String>,
    val reason: String,
    val evidence: String,
    val uncertainty: String,
    val next: String,
    val at: Long,
) {
    /** Whether the agent said anything beyond the two required fields. */
    val hasDetail: Boolean
        get() = problem.isNotBlank() || approach.isNotBlank() || alternatives.isNotEmpty() ||
            evidence.isNotBlank() || uncertainty.isNotBlank() || next.isNotBlank()
}

/**
 * One check the agent recorded, with how far it got.
 *
 * [level] IS THE VOCABULARY WORD AND [supportsCompletion] IS DERIVED FROM IT, so a record cannot
 * claim a level it did not earn. The room shows both the words a person reads and whether the claim
 * it backs may be reported as finished, which is the difference between "it says it is done" and "it
 * checked and here is what it saw".
 */
data class VerificationView(
    /** One-based within the run, so "the second thing it checked" is answerable. */
    val sequence: Int,
    val claim: String,
    val level: String,
    val evidence: String,
    val check: String,
    val uncertainty: String,
    val at: Long,
) {
    /** Whether a completion claim may rest on this check. */
    val supportsCompletion: Boolean get() = VerificationLevels.supportsCompletion(level)

    /** The level in words a person reads, never the raw constant on its own. */
    val levelLabel: String get() = VerificationLevels.label(level)

    /** Whether the agent said anything beyond what was required. */
    val hasDetail: Boolean get() = check.isNotBlank() || uncertainty.isNotBlank()
}

/**
 * One source a run considered, and whether it reached the model.
 *
 * THE TWO FLAGS ARE THE WHOLE POINT. [withinScope] says whether the retrieval was allowed to use it,
 * and [suppliedToModel] says whether it reached the prompt. A source that was in scope and never
 * supplied is the interesting case, because it explains a thin answer without blaming the model: the
 * information was there and the window had no room for it.
 */
data class ContextSourceView(
    val sourceKind: String,
    val sourceLabel: String,
    val retrievalQuery: String,
    val withinScope: Boolean,
    val scopeLabel: String,
    val suppliedToModel: Boolean,
    val excerpt: String,
    val rankInLane: Int,
) {
    /** A name a person can read, falling back to the kind when the retrieval named nothing. */
    val displayName: String
        get() = sourceLabel.ifBlank { sourceKind.lowercase().replace('_', ' ') }
}

/** One output, with where it lives. */
data class ArtefactView(
    val artefactId: String,
    val title: String,
    val kind: String,
    val storageKind: String,
    val storageRef: String,
    val sizeBytes: Long,
    val finalOutput: Boolean,
    val at: Long,
)

/** One decision waiting on the person, with the six facts they need. */
data class ApprovalView(
    val approvalId: String,
    val action: String,
    val what: String,
    val why: String,
    val affected: String,
    val reversible: Boolean,
    val consequences: String,
    val requestedPermission: String,
    val requestedAt: Long,
)

/** One previous run, for the history list. */
data class RunSummaryView(
    val runId: String,
    val assignment: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val artefactCount: Int,
    val toolCallCount: Int,
) {
    /** How long ago it finished, in words, from real timestamps. */
    fun relativeAge(now: Long = System.currentTimeMillis()): String {
        val end = finishedAt ?: startedAt
        val delta = (now - end).coerceAtLeast(0)
        val minutes = delta / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }
}
