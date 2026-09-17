package com.unuslumen.app.data.hooks

import com.unuslumen.app.domain.model.HookExecutionResult
import com.unuslumen.app.domain.model.HookEventType
import com.unuslumen.app.domain.repository.HookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

/**
 * HookEventBus — the single dispatch point for automatic hook firing.
 *
 * The engine (AiRepositoryImpl), task executor and conversation lifecycle all
 * call [fire] when real events happen. Every enabled hook registered for that
 * event type runs its condition evaluation and action through
 * HookRepository.executeHooks, asynchronously and off the caller's critical
 * path. A hook crash never blocks or fails the event's host operation.
 *
 * Data conventions for the event maps:
 *  - MESSAGE_SENT:      conversationId, content, messageId
 *  - MESSAGE_RECEIVED:  conversationId, content, messageId
 *  - CONVERSATION_STARTED: conversationId, firstMessage
 *  - CONVERSATION_ENDED:   conversationId, messageCount
 *  - TASK_*:           taskId, title, completed
 *
 * BEFORE/AFTER timing both get dispatched; hooks pick their side via
 * triggerTiming and the repository filters by (eventType, timing).
 */
object HookEventBus {

    private var hookRepository: HookRepository? = null
    private var scope: CoroutineScope? = null
    private val fireMutex = Mutex()

    /** Called once from Koin module wiring (Application init) — safe to call before any fires. */
    fun init(repository: HookRepository, coroutineScope: CoroutineScope) {
        hookRepository = repository
        scope = coroutineScope
    }

    /**
     * Fire hooks for an event. Fire-and-forget: never throws to the caller,
     * never delays the host operation. Data values should be flat JSON-friendly
     * types (String, Number, Boolean) so condition field paths resolve.
     */
    fun fire(eventType: HookEventType, data: Map<String, Any?> = emptyMap()) {
        val repo = hookRepository
        val coroutineScope = scope
        if (repo == null || coroutineScope == null) {
            Log.w("guru_hooks", "HookEventBus not initialised, dropping event $eventType")
            return
        }
        coroutineScope.launch {
            try {
                // A hook action may itself fire more events; serialise to keep
                // the loop bounded and per-hook results readable.
                fireMutex.withLock {
                    val results: List<HookExecutionResult> = repo.executeHooks(
                        eventType,
                        com.unuslumen.app.domain.model.TriggerTiming.AFTER,
                        data
                    )
                    if (results.isNotEmpty()) {
                        Log.d("guru_hooks", "$eventType fired ${results.size} hook(s): " +
                            results.joinToString { "${it.hookName}=${it.success}" })
                    }
                }
            } catch (e: Exception) {
                Log.w("guru_hooks", "Hook dispatch failed for $eventType: ${e.message}")
            }
        }
    }
}