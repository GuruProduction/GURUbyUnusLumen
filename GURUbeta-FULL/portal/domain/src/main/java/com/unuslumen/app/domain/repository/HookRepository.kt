package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface HookRepository {
    
    /**
     * Create a new hook.
     */
    suspend fun createHook(request: CreateHookRequest): GuruHook
    
    /**
     * Get all hooks.
     */
    suspend fun getAllHooks(): List<GuruHook>
    
    /**
     * Get all hooks as a flow.
     */
    fun getAllHooksFlow(): Flow<List<GuruHook>>
    
    /**
     * Get enabled hooks.
     */
    suspend fun getEnabledHooks(): List<GuruHook>
    
    /**
     * Get enabled hooks as a flow.
     */
    fun getEnabledHooksFlow(): Flow<List<GuruHook>>
    
    /**
     * Get hooks for a specific event type.
     */
    suspend fun getHooksForEvent(eventType: HookEventType): List<GuruHook>
    
    /**
     * Get hooks for a specific event type and timing.
     */
    suspend fun getHooksForEventAndTiming(
        eventType: HookEventType,
        timing: TriggerTiming
    ): List<GuruHook>
    
    /**
     * Get a hook by ID.
     */
    suspend fun getHook(id: String): GuruHook?
    
    /**
     * Get a hook by name.
     */
    suspend fun getHookByName(name: String): GuruHook?
    
    /**
     * Update a hook.
     */
    suspend fun updateHook(id: String, request: CreateHookRequest): GuruHook
    
    /**
     * Enable a hook.
     */
    suspend fun enableHook(id: String): GuruHook
    
    /**
     * Disable a hook.
     */
    suspend fun disableHook(id: String): GuruHook
    
    /**
     * Delete a hook.
     */
    suspend fun deleteHook(id: String)
    
    /**
     * Execute hooks for an event.
     * Returns results of all executed hooks.
     */
    suspend fun executeHooks(
        eventType: HookEventType,
        timing: TriggerTiming,
        eventData: Map<String, Any?>
    ): List<HookExecutionResult>
    
    /**
     * Record that a hook was triggered.
     */
    suspend fun recordTrigger(id: String)
    
    /**
     * Get summary statistics.
     */
    suspend fun getSummary(): HooksSummary
    
    /**
     * Validate a hook definition.
     */
    suspend fun validateHook(request: CreateHookRequest): ValidationResult
}