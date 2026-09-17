package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * When the hook should fire relative to the event.
 */
enum class TriggerTiming {
    BEFORE,  // Fire before the event occurs
    AFTER    // Fire after the event completes
}

/**
 * Types of events that can trigger hooks.
 */
enum class HookEventType(val displayName: String, val description: String) {
    // Task events
    TASK_CREATED("Task Created", "Fires when a new task is created"),
    TASK_UPDATED("Task Updated", "Fires when a task is modified"),
    TASK_COMPLETED("Task Completed", "Fires when a task is marked complete"),
    TASK_DELETED("Task Deleted", "Fires when a task is deleted"),
    
    // Note events
    NOTE_CREATED("Note Created", "Fires when a new note is created"),
    NOTE_UPDATED("Note Updated", "Fires when a note is modified"),
    NOTE_DELETED("Note Deleted", "Fires when a note is deleted"),
    
    // Calendar events
    EVENT_CREATED("Event Created", "Fires when a calendar event is created"),
    EVENT_UPDATED("Event Updated", "Fires when a calendar event is modified"),
    EVENT_DELETED("Event Deleted", "Fires when a calendar event is deleted"),
    EVENT_STARTING("Event Starting", "Fires before a calendar event starts"),
    
    // Journal events
    JOURNAL_ENTRY_CREATED("Journal Entry Created", "Fires when a journal entry is created"),
    JOURNAL_ENTRY_UPDATED("Journal Entry Updated", "Fires when a journal entry is modified"),
    
    // Bookmark events
    BOOKMARK_CREATED("Bookmark Created", "Fires when a bookmark is created"),
    BOOKMARK_DELETED("Bookmark Deleted", "Fires when a bookmark is deleted"),
    
    // Conversation events
    CONVERSATION_STARTED("Conversation Started", "Fires when a new conversation begins"),
    CONVERSATION_ENDED("Conversation Ended", "Fires when a conversation ends"),
    MESSAGE_SENT("Message Sent", "Fires when a message is sent"),
    MESSAGE_RECEIVED("Message Received", "Fires when a message is received"),
    
    // System events
    APP_STARTED("App Started", "Fires when the app launches"),
    APP_FOREGROUND("App Foreground", "Fires when the app comes to foreground"),
    APP_BACKGROUND("App Background", "Fires when the app goes to background"),
    TIME_CHANGED("Time Changed", "Fires when the system time changes significantly"),
    
    // Theme events
    THEME_CHANGED("Theme Changed", "Fires when the app's theme is changed by Guru or the user"),
    
    // Custom events
    CUSTOM("Custom Event", "Custom event triggered by other hooks or skills")
}

/**
 * Condition expression for hook execution.
 */
@Serializable
data class HookCondition(
    val field: String,          // Field to check (e.g., "task.priority")
    val operator: String,       // Comparison operator (eq, neq, gt, lt, gte, lte, contains, matches)
    val value: String           // Value to compare against
)

/**
 * Action to execute when hook fires.
 */
@Serializable
data class HookAction(
    val type: String,           // Type of action: "skill", "tool", "notification"
    val target: String,         // Skill name, tool name, or notification type
    val params: Map<String, String> = emptyMap(),  // Parameters for the action
    val async: Boolean = true   // Whether to run asynchronously
)

/**
 * A hook definition.
 */
@Serializable
data class GuruHook(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val eventType: HookEventType,
    val triggerTiming: TriggerTiming,
    val condition: HookCondition?,   // Optional condition
    val action: HookAction,
    val priority: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTriggeredAt: Long?,
    val triggerCount: Int
)

/**
 * Request to create a new hook.
 */
@Serializable
data class CreateHookRequest(
    val name: String,
    val displayName: String,
    val description: String,
    val eventType: HookEventType,
    val triggerTiming: TriggerTiming,
    val condition: HookCondition?,
    val action: HookAction,
    val priority: Int = 100
)

/**
 * Result of hook execution.
 */
@Serializable
data class HookExecutionResult(
    val hookId: String,
    val hookName: String,
    val eventType: String,
    val triggeredAt: Long,
    val success: Boolean,
    val actionResult: String?,
    val error: String?,
    val executionTimeMs: Long
)

/**
 * Summary of hooks.
 */
@Serializable
data class HooksSummary(
    val totalHooks: Int,
    val enabledHooks: Int,
    val beforeHooks: Int,
    val afterHooks: Int,
    val totalTriggers: Int,
    val byEventType: Map<String, Int>
)