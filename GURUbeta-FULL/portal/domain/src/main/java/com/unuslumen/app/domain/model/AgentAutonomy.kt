// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * What an agent may do on its own, and where it has to involve somebody.
 *
 * AUTONOMY IS NOT ONE SWITCH. The first draft of this feature had a boolean that either let an
 * agent act or stopped it acting, which tells a person nothing about what they are permitting and
 * cannot express the actual question: "may it use the tools it was given, but not message me at
 * three in the morning, and not touch memory without asking?"
 *
 * AUTONOMY IS ALSO NOT VISIBILITY. They are separate fields on purpose and the separation is the
 * point. An agent permitted to act independently can still be required to show its work, and an
 * agent required to ask before acting can have its reasoning hidden. Conflating the two produces
 * the arrangement nobody wants: transparency granted in exchange for obedience.
 *
 * EVERY FIELD DEFAULTS TO THE PERMISSIVE-ENOUGH SET, with the genuinely consequential ones off.
 * A new agent may plan, use its declared tools and produce artefacts, because those are what it
 * was created to do. It may not perform external side effects or operate on a schedule until
 * somebody says so, because those reach past the phone.
 *
 * THIS TYPE DECLARES POLICY AND THE RUNTIME ENFORCES IT. Where a field's enforcement lands in a
 * later phase, the field says so in its own doc rather than pretending it is already in force.
 */
@Serializable
data class AgentAutonomy(
    /** May form and revise its own plan without asking first. */
    val planIndependently: Boolean = true,

    /**
     * May call the tools it was granted without asking each time.
     *
     * This does not widen the grant. A tool outside the agent's declared set is refused by the
     * dispatcher whatever this says.
     */
    val useDeclaredToolsWithoutAsking: Boolean = true,

    /** May open a conversation with the owner rather than only answering. */
    val contactOwnerProactively: Boolean = true,

    /** May ask GURU to delegate part of the work to another agent. */
    val requestDelegation: Boolean = false,

    /** May create and revise artefacts. The artefact store itself is Phase 6. */
    val createOrModifyArtefacts: Boolean = true,

    /** May propose persistent memory changes. Writing them is Phase 6 and is always approved. */
    val proposeMemoryChanges: Boolean = true,

    /**
     * May act on things outside the phone: network calls, messages, purchases, publishing.
     *
     * OFF BY DEFAULT. This is the one dimension where an accident is not recoverable, so the
     * default is the narrow one and turning it on is a deliberate act.
     */
    val performExternalSideEffects: Boolean = false,

    /**
     * Keeps working when the owner closes the room.
     *
     * ON BY DEFAULT, and it is not the same as running in the background for ever: Android can
     * kill the process, and the run then recovers from its checkpoint rather than continuing
     * live. See the reconciliation for the honest ceiling.
     */
    val continueAfterRoomClosed: Boolean = true,

    /** May be started by a schedule or a trigger rather than by a request. */
    val operateOnSchedules: Boolean = false,

    /**
     * Actions that stop and ask even though the dimension above permits them.
     *
     * A SECOND AXIS RATHER THAN A CONTRADICTION. Autonomy says what is allowed in general;
     * this says which of those allowed things still needs a yes. "It may send messages, but ask
     * me before each one" is a real posture and neither field alone can express it.
     */
    val requireApprovalFor: Set<ApprovalTrigger> = emptySet(),
) {
    /** The dimensions as a map, so a settings surface iterates rather than hardcoding ten. */
    fun asMap(): Map<AutonomyDimension, Boolean> = mapOf(
        AutonomyDimension.PLAN_INDEPENDENTLY to planIndependently,
        AutonomyDimension.USE_TOOLS_WITHOUT_ASKING to useDeclaredToolsWithoutAsking,
        AutonomyDimension.CONTACT_OWNER_PROACTIVELY to contactOwnerProactively,
        AutonomyDimension.REQUEST_DELEGATION to requestDelegation,
        AutonomyDimension.CREATE_OR_MODIFY_ARTEFACTS to createOrModifyArtefacts,
        AutonomyDimension.PROPOSE_MEMORY_CHANGES to proposeMemoryChanges,
        AutonomyDimension.PERFORM_EXTERNAL_SIDE_EFFECTS to performExternalSideEffects,
        AutonomyDimension.CONTINUE_AFTER_ROOM_CLOSED to continueAfterRoomClosed,
        AutonomyDimension.OPERATE_ON_SCHEDULES to operateOnSchedules,
    )

    fun permits(dimension: AutonomyDimension): Boolean = asMap().getValue(dimension)

    /** Does this action need a yes, regardless of whether the dimension permits it? */
    fun needsApproval(trigger: ApprovalTrigger): Boolean = trigger in requireApprovalFor

    companion object {
        /** The default a new agent is created with: useful, and careful about the outside world. */
        val DEFAULT = AgentAutonomy()

        /** Reads and reasons, changes nothing. */
        val READ_ONLY = AgentAutonomy(
            useDeclaredToolsWithoutAsking = false,
            createOrModifyArtefacts = false,
            proposeMemoryChanges = false,
            performExternalSideEffects = false,
            operateOnSchedules = false,
        )
    }
}

/** The nine autonomy dimensions from the specification, as a type rather than a paragraph. */
enum class AutonomyDimension(val label: String, val explanation: String) {
    PLAN_INDEPENDENTLY("Plan alone", "Form and revise its own plan without asking first."),
    USE_TOOLS_WITHOUT_ASKING("Use its tools", "Call the tools it was granted without asking each time."),
    CONTACT_OWNER_PROACTIVELY("Speak first", "Start a conversation rather than only answering."),
    REQUEST_DELEGATION("Ask for help", "Request that GURU delegate part of the work to another agent."),
    CREATE_OR_MODIFY_ARTEFACTS("Make things", "Create and revise files, documents and outputs."),
    PROPOSE_MEMORY_CHANGES("Remember", "Propose persistent changes to memory."),
    PERFORM_EXTERNAL_SIDE_EFFECTS("Reach outside", "Act on things beyond this phone."),
    CONTINUE_AFTER_ROOM_CLOSED("Keep going", "Carry on when you leave its room."),
    OPERATE_ON_SCHEDULES("Run on a schedule", "Start itself from a schedule or a trigger."),
}

/**
 * An action that requires an explicit yes.
 *
 * DECLARED HERE SO THE CONFIGURATION IS COMPLETE, ENFORCED IN PHASE 5. A trigger with no runtime
 * behind it is worse than no trigger at all if the interface implies it works, so the settings
 * surface labels these as taking effect once the approval service exists.
 */
enum class ApprovalTrigger(val label: String) {
    EXTERNAL_SIDE_EFFECT("Acting outside the phone"),
    MEMORY_WRITE("Changing memory"),
    DELEGATION("Handing work to another agent"),
    ARTEFACT_PUBLICATION("Publishing an output"),
    TOOL_EXECUTION("Running a tool"),
}
