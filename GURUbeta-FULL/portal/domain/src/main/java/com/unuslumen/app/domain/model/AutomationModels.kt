package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Trigger type for an automation.
 */
enum class AutomationTrigger {
    MANUAL,     // Only runs when explicitly called
    SCHEDULED,  // Runs on a schedule
    EVENT       // Runs when an event occurs
}

/**
 * Configuration for a scheduled automation.
 */
@Serializable
data class ScheduleConfig(
    val cron: String? = null,           // Cron expression for scheduling
    val interval: Long? = null,        // Interval in milliseconds
    val startTime: Long? = null       // Start time as epoch millis
)

/**
 * Configuration for an event-triggered automation.
 */
@Serializable
data class EventConfig(
    val eventType: String,              // Event type that triggers the automation
    val conditions: Map<String, String> = emptyMap()  // Conditions for triggering
)

/**
 * A step in an automation execution.
 */
@Serializable
data class AutomationStep(
    val tool: String,                  // Tool name to call
    val params: Map<String, String>,   // Parameters (can reference ${params.xxx})
    val output: String                 // Variable name for output
)

/**
 * Represents an automation defined by Guru.
 */
@Serializable
data class GuruAutomation(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val trigger: AutomationTrigger,
    val triggerConfig: String,         // JSON: ScheduleConfig or EventConfig
    val steps: List<AutomationStep>,
    val createdAt: Long,
    val lastRunAt: Long?,
    val runCount: Int,
    val enabled: Boolean
)

/**
 * Request to create a new automation.
 */
@Serializable
data class CreateAutomationRequest(
    val name: String,
    val displayName: String,
    val description: String,
    val trigger: AutomationTrigger,
    val triggerConfig: String? = null,
    val steps: List<AutomationStep>
)

/**
 * Result of automation execution.
 */
@Serializable
data class AutomationExecutionResult(
    val success: Boolean,
    val results: List<StepResult>,
    val executionTimeMs: Long,
    val error: String? = null
)

/**
 * Result of a single step execution.
 */
@Serializable
data class StepResult(
    val step: Int,
    val tool: String,
    val success: Boolean,
    val result: String?,
    val error: String? = null
)

/**
 * Summary of Guru's automations.
 */
@Serializable
data class AutomationSummary(
    val totalAutomations: Int,
    val enabledAutomations: Int,
    val manualAutomations: Int,
    val scheduledAutomations: Int,
    val eventAutomations: Int,
    val totalRuns: Int
)
