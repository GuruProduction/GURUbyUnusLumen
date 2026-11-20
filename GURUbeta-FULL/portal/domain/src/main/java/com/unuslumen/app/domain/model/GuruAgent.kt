// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * One agent definition the numen can call by name.
 *
 * [slug] is the catalogue identity once the agent has been published or
 * downloaded, and empty for one that only ever exists on this phone. [source]
 * is "user", "synced" or "bundled" and it is the trust boundary: the sync
 * worker may overwrite or withdraw a synced agent and must never touch a user
 * one. [version] is the applied publication version.
 *
 * FOUR THINGS THAT USED TO BE ONE. The specification separates what an agent IS from how it
 * OPERATES from what it can DO from what it is ALLOWED to do, and this type now carries all four
 * rather than one prompt and a tool list:
 *
 *  - identity and instructions: name, description, [systemPrompt], [visual]
 *  - operating preferences: [autonomy], [budgets], [modelPreference], [notifications]
 *  - declared capability: [toolPermissions], [skillIds], [memoryScope], [contextScope]
 *  - transparency: [visibility]
 *
 * EVERY NEW FIELD HAS A DEFAULT MATCHING THE OLD BEHAVIOUR, because this type is read from rows
 * that predate it. An agent created before this change loads with transparent visibility, the
 * default autonomy and no budgets, which is exactly what it did before and is now stated rather
 * than implied.
 *
 * [definitionVersion] IS NOT [version]. [version] is the published catalogue version the sync
 * worker force-pushes on. [definitionVersion] counts local edits, and an edit is what appends a
 * row to the definition history so a previous prompt or tool set survives.
 */
@Serializable
data class GuruAgent(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolPermissions: List<String>,
    val source: String,
    val enabled: Boolean = true,
    val version: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,

    /** Stored visual identity so an agent keeps its colour rather than taking the list's index. */
    val visual: AgentVisual = AgentVisual.DEFAULT,

    /** How it shows its work. Eleven categories, each independently captured and displayed. */
    val visibility: AgentVisibility = AgentVisibility.TRANSPARENT,

    /** What it may do without asking. Separate from visibility by design. */
    val autonomy: AgentAutonomy = AgentAutonomy.DEFAULT,

    /** Execution limits. Null means the system default applies rather than "no limit". */
    val budgets: AgentBudgets = AgentBudgets.DEFAULT,

    /** Which provider and model this agent prefers, with an explicit fallback policy. */
    val modelPreference: AgentModelPreference = AgentModelPreference.INHERIT,

    /** How and when it may interrupt its owner. */
    val notifications: AgentNotifications = AgentNotifications.DEFAULT,

    /** Skill ids granted, alongside the tools. Skills describe procedure; tools do the work. */
    val skillIds: List<String> = emptyList(),

    /** Which memory scopes this agent may read or write. NONE means none. */
    val memoryScope: AgentMemoryScope = AgentMemoryScope.NONE,

    /** Which context sources this agent may be given. ASSIGNMENT_ONLY means the job text only. */
    val contextScope: AgentContextScope = AgentContextScope.ASSIGNMENT_ONLY,

    /** The machine-readable manifest, validated before execution. */
    val manifest: AgentCapabilityManifest = AgentCapabilityManifest.EMPTY,

    /** Counts local edits, and is what a definition-history row is keyed on. */
    val definitionVersion: Int = 1,
) {
    /** True when this agent is the person's own and cannot be overwritten or withdrawn. */
    val isUserOwned: Boolean get() = source == SOURCE_USER

    /**
     * The definition as values, for freezing into a run.
     *
     * A RUN SNAPSHOTS VALUES, NOT A ROW. `AgentRunEntity.agentDefinitionSnapshotJson` is written
     * from this at creation, so a later edit to the agent cannot change what an old run says it
     * was given. Returning a serializable object rather than a string keeps the shape and the
     * snapshot in one place instead of two that can drift.
     */
    fun definitionSnapshot(): AgentDefinitionSnapshot = AgentDefinitionSnapshot(
        agentId = id,
        slug = slug,
        name = name,
        description = description,
        systemPrompt = systemPrompt,
        toolPermissions = toolPermissions,
        skillIds = skillIds,
        source = source,
        version = version,
        definitionVersion = definitionVersion,
        visual = visual,
        visibility = visibility,
        autonomy = autonomy,
        budgets = budgets,
        modelPreference = modelPreference,
        notifications = notifications,
        memoryScope = memoryScope,
        contextScope = contextScope,
        manifest = manifest,
    )

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_SYNCED = "synced"
        const val SOURCE_BUNDLED = "bundled"
    }
}

/**
 * The exact definition values a run was given, frozen at creation.
 *
 * WHY THIS EXISTS RATHER THAN A VERSION NUMBER. The agent row is mutable and its history table is
 * optional to read. A snapshot that names a version still requires the reader to trust that the
 * history row with that number is the one that ran. Carrying the values removes the join and
 * removes the assumption, which is what the specification's section 10.3 requires: a previous
 * run's snapshot is never rewritten by a later edit.
 */
@Serializable
data class AgentDefinitionSnapshot(
    val agentId: String,
    val slug: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolPermissions: List<String>,
    val skillIds: List<String>,
    val source: String,
    val version: Int,
    val definitionVersion: Int,
    val visual: AgentVisual,
    val visibility: AgentVisibility,
    val autonomy: AgentAutonomy,
    val budgets: AgentBudgets,
    val modelPreference: AgentModelPreference,
    val notifications: AgentNotifications,
    val memoryScope: AgentMemoryScope,
    val contextScope: AgentContextScope,
    val manifest: AgentCapabilityManifest,
    val schemaVersion: Int = 1,
)

/** Stored identity: the colour and mark an agent keeps wherever it is drawn. */
@Serializable
data class AgentVisual(
    /** Index into the house colour rotation, so a colour is data rather than list position. */
    val colourIndex: Int = 0,
    /** A short mark for a presence node. Never a network image, which could fail silently. */
    val initials: String = "",
) {
    companion object {
        val DEFAULT = AgentVisual()
    }
}

/** Execution limits for one agent. Null on any field means the system default applies. */
@Serializable
data class AgentBudgets(
    /** Cap on assistant exchanges in one run. Null uses the runtime default. */
    val maxExchanges: Int? = null,
    /** Cap on total model requests in one run. */
    val maxModelRequests: Int? = null,
    /** Cap on tool invocations in one run. */
    val maxToolCalls: Int? = null,
    /** Wall-clock ceiling in milliseconds for one run. */
    val maxDurationMillis: Long? = null,
    /** How many delegated children this agent may create across its life. Phase 8 enforces it. */
    val maxDelegations: Int? = null,
    /** How deep a chain this agent may start. Phase 8 enforces it. */
    val maxDelegationDepth: Int? = null,
) {
    companion object {
        /** All null: the system defaults govern, which is what every existing agent gets. */
        val DEFAULT = AgentBudgets()
    }
}

/** Which model an agent prefers, and what to do when it is unavailable. */
@Serializable
data class AgentModelPreference(
    /** Empty means inherit whatever the phone is configured with. */
    val providerId: String = "",
    val modelName: String = "",
    /** When the preferred model cannot be used: fall back to the device model, or refuse. */
    val fallback: ModelFallback = ModelFallback.USE_DEVICE_MODEL,
) {
    companion object {
        val INHERIT = AgentModelPreference()
    }
}

/**
 * What happens when a preferred model is not available.
 *
 * REFUSING IS A REAL CHOICE AND IS NOT A FAILURE. An agent tuned for one model may behave badly on
 * another, and a person who said "this one only" should get an honest refusal rather than a
 * silently different agent doing the job.
 */
@Serializable
enum class ModelFallback {
    USE_DEVICE_MODEL,
    REFUSE,
}

/** How an agent may interrupt its owner. */
@Serializable
data class AgentNotifications(
    /** May notify at all. Off means findings wait until the room is opened. */
    val enabled: Boolean = true,
    /** Only notify for things at or above this urgency. */
    val minUrgency: AgentUrgency = AgentUrgency.NORMAL,
    /** Do not notify during these hours, in local minutes from midnight. */
    val quietFromMinute: Int? = null,
    val quietToMinute: Int? = null,
) {
    companion object {
        val DEFAULT = AgentNotifications()
    }

    /** Is a given minute of the day inside the quiet window? Handles a window across midnight. */
    fun isQuietAt(minuteOfDay: Int): Boolean {
        val from = quietFromMinute ?: return false
        val to = quietToMinute ?: return false
        if (from == to) return true
        return if (from < to) minuteOfDay in from until to else minuteOfDay >= from || minuteOfDay < to
    }
}

/** How urgent a communication is, used to decide whether it may interrupt. */
@Serializable
enum class AgentUrgency {
    LOW,
    NORMAL,
    HIGH,
    BLOCKING,
}

/**
 * Which memory an agent may touch.
 *
 * NONE IS THE DEFAULT AND IT IS NOT A LIMITATION, IT IS THE HONEST STARTING POINT. An agent that
 * has been granted no memory scope reads nothing and writes nothing, and is told so by the
 * runtime, rather than discovering at the point of use that a call returned empty.
 */
@Serializable
data class AgentMemoryScope(
    /** May read shared GURU knowledge. */
    val readShared: Boolean = false,
    /** May read memory this agent wrote itself. */
    val readOwn: Boolean = true,
    /** May write memory attributed to this agent. */
    val writeOwn: Boolean = false,
    /** May propose additions to shared knowledge. These are proposals, never direct writes. */
    val proposeShared: Boolean = false,
    /** Categories it may read, empty meaning every category in scope. */
    val categories: Set<String> = emptySet(),
) {
    companion object {
        val NONE = AgentMemoryScope(readShared = false, readOwn = false, writeOwn = false)
        val OWN_ONLY = AgentMemoryScope()
    }
}

/** Which context sources an agent may be given beyond its own assignment. */
@Serializable
data class AgentContextScope(
    val includeConversation: Boolean = false,
    val includeNotes: Boolean = false,
    val includeProjects: Boolean = false,
    val includeFiles: Boolean = false,
    val includeMemory: Boolean = false,
) {
    companion object {
        /** The job text and nothing else. What an agent got before this change. */
        val ASSIGNMENT_ONLY = AgentContextScope()
    }
}

/**
 * What an agent declares it needs, so it can be validated before it is allowed to run.
 *
 * WHY A MANIFEST RATHER THAN INFERRING FROM THE TOOL LIST. A tool list says which tools were
 * granted; it does not say which provider capability, which runtime version or which integration
 * the agent actually depends on. Without that, an agent that needs a reasoning channel silently
 * behaves differently on a model without one, and its owner has no way to know why.
 *
 * [EMPTY] is the manifest of an agent that declares nothing, which validates against any runtime
 * because it asks for nothing.
 */
@Serializable
data class AgentCapabilityManifest(
    val manifestVersion: Int = 1,
    /** Tools the agent declares it needs. A required tool that is not granted fails validation. */
    val requiredTools: List<String> = emptyList(),
    /** Provider capabilities it needs. Absent means it does not care. */
    val requiresReasoningChannel: Boolean = false,
    val requiresToolCalling: Boolean = true,
    val requiresStreaming: Boolean = true,
    /** The narrowest runtime version it is known to work on. */
    val minimumRuntimeSchemaVersion: Int = 1,
    /** Android permissions it will ask the device for, declared for the owner to see up front. */
    val requiredPermissions: List<String> = emptyList(),
    /** Integration ids it depends on. */
    val requiredIntegrations: List<String> = emptyList(),
    /** Input and output types it claims to handle, for a catalogue to filter on. */
    val inputTypes: List<String> = emptyList(),
    val outputTypes: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = AgentCapabilityManifest()
    }
}

/** A create or edit request from the creator screen. */
@Serializable
data class SaveAgentRequest(
    val name: String,
    val description: String = "",
    val systemPrompt: String,
    val toolPermissions: List<String> = emptyList(),
    /** Present when editing an existing agent's operating configuration. Defaults for a new one. */
    val visual: AgentVisual = AgentVisual.DEFAULT,
    val visibility: AgentVisibility = AgentVisibility.TRANSPARENT,
    val autonomy: AgentAutonomy = AgentAutonomy.DEFAULT,
    val budgets: AgentBudgets = AgentBudgets.DEFAULT,
    val modelPreference: AgentModelPreference = AgentModelPreference.INHERIT,
    val notifications: AgentNotifications = AgentNotifications.DEFAULT,
    val skillIds: List<String> = emptyList(),
    val memoryScope: AgentMemoryScope = AgentMemoryScope.NONE,
    val contextScope: AgentContextScope = AgentContextScope.ASSIGNMENT_ONLY,
    val manifest: AgentCapabilityManifest = AgentCapabilityManifest.EMPTY,
)

/** Counts for the agents list header. */
@Serializable
data class AgentSummary(
    val total: Int,
    val enabled: Int,
    val userMade: Int,
    val downloaded: Int
)
