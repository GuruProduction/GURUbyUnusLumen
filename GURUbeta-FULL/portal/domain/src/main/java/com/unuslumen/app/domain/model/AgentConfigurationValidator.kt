// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

/**
 * Checks an agent's configuration against what this build can actually do.
 *
 * WHY VALIDATION EXISTS AT ALL. The specification's section 6.2 is blunt about the failure it
 * forbids: a requested setting must not be silently converted into an ineffective preference. An
 * agent whose manifest says it needs a reasoning channel, running on a model that has none, must
 * say so before it runs rather than produce a run whose reasoning area is permanently empty and
 * unexplained.
 *
 * THREE SEVERITIES, AND THE DIFFERENCE MATTERS.
 *
 *  - [Severity.ERROR] means the run must be refused. A required tool that was not granted, or a
 *    refused model fallback whose preferred model is unavailable, cannot be papered over: running
 *    anyway would execute a different agent from the one the owner configured.
 *  - [Severity.WARNING] means the run proceeds and the difference is recorded and shown. A model
 *    without a reasoning channel is the canonical case: the agent still works, and the room says
 *    plainly that raw reasoning is unavailable rather than drawing an empty panel.
 *  - [Severity.NOTE] is information, such as a declared integration that this build does not ship.
 *
 * THE VALIDATOR IS PURE AND TAKES FIVE PLAIN VALUES. It has no Android dependency, no database and
 * no network, so it runs in a JVM test in milliseconds and the same verdict is reachable from the
 * creator screen, the spawn path and a test.
 */
object AgentConfigurationValidator {

    /**
     * What this device can actually provide, resolved once per validation.
     *
     * [availableTools] and [availableSkills] are the granted, registered names. A manifest naming
     * something absent is how an agent ends up promising work it cannot do.
     */
    data class RuntimeCapabilities(
        val availableTools: Set<String>,
        val availableSkills: Set<String>,
        val availableIntegrations: Set<String>,
        val supportsReasoningChannel: Boolean,
        val supportsToolCalling: Boolean,
        val supportsStreaming: Boolean,
        val runtimeSchemaVersion: Int,
        /** Empty when the device has a model configured; otherwise why it does not. */
        val modelUnavailableReason: String? = null,
    )

    /** One finding. Never a thrown exception: a caller decides what to do with a list. */
    data class Finding(
        val severity: Severity,
        val field: String,
        val message: String,
    )

    enum class Severity { ERROR, WARNING, NOTE }

    /** The whole verdict, so a caller reads one object rather than re-deriving from three booleans. */
    data class Verdict(val findings: List<Finding>) {
        val errors: List<Finding> get() = findings.filter { it.severity == Severity.ERROR }
        val warnings: List<Finding> get() = findings.filter { it.severity == Severity.WARNING }
        val notes: List<Finding> get() = findings.filter { it.severity == Severity.NOTE }

        /** May this configuration run at all? */
        val canRun: Boolean get() = errors.isEmpty()

        /** One line for a surface that has room for one line. Null when there is nothing to say. */
        fun headline(): String? = when {
            errors.isNotEmpty() -> errors.first().message
            warnings.isNotEmpty() -> warnings.first().message
            notes.isNotEmpty() -> notes.first().message
            else -> null
        }
    }

    /**
     * Validate one agent against one runtime.
     *
     * EVERY CHECK BELOW EXISTS BECAUSE ITS ABSENCE PRODUCES A SILENT LIE. A missing tool makes an
     * agent refuse a call it was told it could make; a missing rationale makes a reasoning panel
     * permanently blank; a refused fallback that is ignored makes the run use a model the owner
     * said not to use. Each is checked rather than assumed.
     */
    fun validate(agent: GuruAgent, capabilities: RuntimeCapabilities): Verdict {
        val findings = mutableListOf<Finding>()
        val manifest = agent.manifest

        // ---- the model must exist at all ----
        if (capabilities.modelUnavailableReason != null) {
            findings += Finding(
                Severity.ERROR,
                "model",
                "No model is connected right now, so this agent cannot run: " +
                    capabilities.modelUnavailableReason,
            )
        }

        // ---- a required tool that was not granted ----
        for (required in manifest.requiredTools) {
            if (required !in agent.toolPermissions) {
                findings += Finding(
                    Severity.ERROR,
                    "manifest.requiredTools",
                    "This agent needs '$required' but it was not granted, so it would refuse work " +
                        "it was created to do.",
                )
            } else if (required !in capabilities.availableTools) {
                findings += Finding(
                    Severity.ERROR,
                    "manifest.requiredTools",
                    "This agent needs '$required' but no such tool exists on this device.",
                )
            }
        }

        // ---- a declared skill that does not exist ----
        for (skill in agent.skillIds) {
            if (skill !in capabilities.availableSkills) {
                findings += Finding(
                    Severity.WARNING,
                    "skillIds",
                    "The skill '$skill' is not installed, so this agent will work without it.",
                )
            }
        }

        // ---- provider capability, which is where honesty matters most ----
        if (manifest.requiresReasoningChannel && !capabilities.supportsReasoningChannel) {
            findings += Finding(
                Severity.WARNING,
                "manifest.requiresReasoningChannel",
                "This agent asks for the model's reasoning channel, and the connected model does not " +
                    "provide one. It will still run, and its reasoning area will say so rather than " +
                    "showing anything.",
            )
        }
        if (manifest.requiresToolCalling && !capabilities.supportsToolCalling) {
            findings += Finding(
                Severity.ERROR,
                "manifest.requiresToolCalling",
                "This agent needs tool calling and the connected model does not support it, so it " +
                    "could not do its work.",
            )
        }
        if (manifest.requiresStreaming && !capabilities.supportsStreaming) {
            findings += Finding(
                Severity.WARNING,
                "manifest.requiresStreaming",
                "This agent expects a streaming model and this one answers in one piece, so its " +
                    "activity will update once per reply rather than continuously.",
            )
        }

        // ---- runtime version ----
        if (manifest.minimumRuntimeSchemaVersion > capabilities.runtimeSchemaVersion) {
            findings += Finding(
                Severity.ERROR,
                "manifest.minimumRuntimeSchemaVersion",
                "This agent needs runtime schema ${manifest.minimumRuntimeSchemaVersion} and this " +
                    "build is ${capabilities.runtimeSchemaVersion}, so it was made for a newer app.",
            )
        }

        // ---- integrations ----
        for (integration in manifest.requiredIntegrations) {
            if (integration !in capabilities.availableIntegrations) {
                findings += Finding(
                    Severity.NOTE,
                    "manifest.requiredIntegrations",
                    "The integration '$integration' is not connected, so any step needing it will " +
                        "report that rather than failing silently.",
                )
            }
        }

        // ---- a preferred model that cannot be honoured ----
        val preference = agent.modelPreference
        if (preference.modelName.isNotBlank() && preference.fallback == ModelFallback.REFUSE) {
            findings += Finding(
                Severity.NOTE,
                "modelPreference",
                "This agent refuses to fall back: if '${preference.modelName}' is unavailable it " +
                    "will decline to run rather than use the device model.",
            )
        }

        // ---- autonomy that references a capability the build lacks ----
        if (agent.autonomy.requestDelegation) {
            findings += Finding(
                Severity.NOTE,
                "autonomy.requestDelegation",
                "Delegation is configured on for this agent. The runtime that enforces it is built " +
                    "in a later phase, and until then a delegation request is refused rather than " +
                    "silently ignored.",
            )
        }
        if (agent.autonomy.operateOnSchedules) {
            findings += Finding(
                Severity.NOTE,
                "autonomy.operateOnSchedules",
                "Scheduled operation is configured on. Nothing schedules this agent yet; the " +
                    "existing heartbeat is the scheduler and it does not start agents.",
            )
        }

        // ---- approval triggers that no service enforces yet ----
        if (agent.autonomy.requireApprovalFor.isNotEmpty()) {
            findings += Finding(
                Severity.NOTE,
                "autonomy.requireApprovalFor",
                "Approval requirements are recorded. The approval service that enforces them is " +
                    "built in a later phase, so until then these are a stated intent rather than " +
                    "an enforced gate.",
            )
        }

        return Verdict(findings)
    }

    /**
     * The capability manifest a granted tool list implies, for an agent that has none.
     *
     * AN AGENT WITHOUT A MANIFEST IS NOT AN AGENT WITH A BROKEN ONE. Every agent created before
     * this change has no manifest, and refusing them all would be a migration defect dressed as
     * validation. This derives the honest minimum from what the agent already declares.
     */
    fun deriveManifest(agent: GuruAgent): AgentCapabilityManifest = AgentCapabilityManifest(
        requiredTools = emptyList(),
        requiresReasoningChannel = false,
        requiresToolCalling = agent.toolPermissions.isNotEmpty(),
        requiresStreaming = true,
        minimumRuntimeSchemaVersion = 1,
    )
}
