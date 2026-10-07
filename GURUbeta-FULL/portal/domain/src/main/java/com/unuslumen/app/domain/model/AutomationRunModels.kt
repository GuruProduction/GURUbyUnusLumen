// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Doctrine status vocabulary for automation runs.
 *
 * There is no FAILED. A run is running, it completed, it self-healed
 * (something went sideways and the numen or the engine recovered, on
 * display), or it needs the human (an honest ask, not an error state).
 * Whatever renders runs maps these to the words allowed on screen:
 * Running, Completed, Self-healed, Needs you.
 */
enum class AutomationRunStatus {
    RUNNING, COMPLETED, SELF_HEALED, NEEDS_ATTENTION
}

/**
 * The path a run arrived by. Manual from conversation, scheduled job, or
 * hook-wrapped event. All three trace identically.
 */
enum class AutomationRunTrigger {
    MANUAL, JOB, HOOK
}

/**
 * One step's trace inside a run, doctrine-shaped: durations tell the
 * timing story, summaries carry what the human should see. Params and
 * results land here as summaries, never raw credentials — trace privacy is
 * doctrine; full detail lives in chat where it belongs.
 */
@Serializable
data class AutomationStepTrace(
    val index: Int,
    val tool: String,
    val success: Boolean,
    val paramsSummary: String? = null,
    val resultSummary: String? = null,
    val error: String? = null,
    val durationMs: Long = 0,
    /** Doctrine: true when a retry happened to make this step succeed — healing, visible. */
    val healed: Boolean = false
)

/**
 * One executed run of an automation, with its full step trace. This is
 * what the Observatory shows live and remembers.
 */
@Serializable
data class GuruAutomationRun(
    val id: String,
    val automationId: String,
    val automationName: String,
    val trigger: AutomationRunTrigger,
    val startedAt: Long,
    val durationMs: Long,
    val status: AutomationRunStatus,
    val steps: List<AutomationStepTrace>,
    val createdAt: Long = startedAt
) {
    /** Doctrine-safe display word. Never "failed". */
    val displayStatus: String
        get() = when (status) {
            AutomationRunStatus.RUNNING -> "Running"
            AutomationRunStatus.COMPLETED -> "Completed"
            AutomationRunStatus.SELF_HEALED -> "Self-healed"
            AutomationRunStatus.NEEDS_ATTENTION -> "Needs you"
        }
}

/**
 * Retention: runs roll, history stays queryable. The newest [keep] runs
 * per automation survive every write; everything older prunes in the same
 * breath so nothing grows unbounded.
 */
object AutomationRunRetention {
    const val RUNS_PER_AUTOMATION = 200
}