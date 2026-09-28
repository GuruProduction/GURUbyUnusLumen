package com.unuslumen.app.data.thoughts

import android.util.Log
import com.unuslumen.app.domain.model.EventThoughtConfig
import com.unuslumen.app.domain.model.HookEventType
import com.unuslumen.app.domain.model.ThoughtTriggerType
import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import kotlinx.serialization.json.Json

/**
 * EventBridge — connects real app events to EVENT-triggered thought cycles.
 *
 * Real events already flow through HookEventBus.fire(...) from the engine
 * (AiRepositoryImpl fires MESSAGE_SENT, MESSAGE_RECEIVED, CONVERSATION_STARTED)
 * and from tool executors (TaskToolExecutor fires TASK_* events). An
 * EVENT-triggered thought cycle was the one part of the system never handed
 * those events.
 *
 * This bridge closes that gap with ONE function: given the fired event type,
 * return every enabled cycle whose EventThoughtConfig names it. The dispatch
 * lives in HookEventBus (which already serialises event handling), resolving
 * EVENT cycles through ThoughtCycleRepositoryImpl. No new event plumbing
 * anywhere in the app: the single existing pipeline now drives cycles.
 */
object EventBridge {

    private const val TAG = "guru_thoughts"

    /**
     * Every enabled cycle whose EVENT config subscribes to [eventType],
     * as (id, name) pairs for logging and execution. Malformed trigger
     * configs are logged and skipped: one broken cycle never blocks the rest.
     */
    suspend fun cycleIdsForEvent(
        repository: ThoughtCycleRepository,
        eventType: HookEventType,
        json: Json
    ): List<Pair<String, String>> {
        return try {
            val enabled = repository.getEnabledCycles()
            enabled.mapNotNull { cycle ->
                if (cycle.triggerType != ThoughtTriggerType.EVENT) return@mapNotNull null
                val config = try {
                    json.decodeFromString<EventThoughtConfig>(cycle.triggerConfig)
                } catch (e: Exception) {
                    Log.w(TAG, "Cycle '${cycle.name}' bad event config: ${e.message}")
                    return@mapNotNull null
                }
                val matched = config.eventTypes.any {
                    it.equals(eventType.name, ignoreCase = true) ||
                        it.equals(eventType.displayName, ignoreCase = true)
                }
                if (matched) cycle.id to cycle.name else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "EventBridge lookup failed for $eventType: ${e.message}")
            emptyList()
        }
    }
}