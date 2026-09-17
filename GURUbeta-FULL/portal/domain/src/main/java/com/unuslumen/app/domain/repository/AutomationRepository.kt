package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.CreateAutomationRequest
import com.unuslumen.app.domain.model.GuruAutomation
import com.unuslumen.app.domain.model.AutomationExecutionResult
import com.unuslumen.app.domain.model.AutomationTrigger
import com.unuslumen.app.domain.model.AutomationSummary
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing Guru's automations.
 *
 * Automations are named, reusable compositions of tool calls.
 * They can be triggered manually, on a schedule, or by events.
 */
interface AutomationRepository {

    // ==================== Automation Management ====================

    /**
     * Create a new automation.
     */
    suspend fun createAutomation(request: CreateAutomationRequest): GuruAutomation

    /**
     * Get all automations.
     */
    suspend fun getAllAutomations(): List<GuruAutomation>

    /**
     * Get all automations as a Flow.
     */
    fun getAllAutomationsFlow(): Flow<List<GuruAutomation>>

    /**
     * Get enabled automations.
     */
    suspend fun getEnabledAutomations(): List<GuruAutomation>

    /**
     * Get enabled automations as a Flow.
     */
    fun getEnabledAutomationsFlow(): Flow<List<GuruAutomation>>

    /**
     * Get automations by trigger type.
     */
    suspend fun getAutomationsByTrigger(trigger: AutomationTrigger): List<GuruAutomation>

    /**
     * Get an automation by ID.
     */
    suspend fun getAutomation(id: String): GuruAutomation?

    /**
     * Get an automation by name.
     */
    suspend fun getAutomationByName(name: String): GuruAutomation?

    /**
     * Update an automation.
     */
    suspend fun updateAutomation(id: String, request: CreateAutomationRequest): GuruAutomation

    /**
     * Enable an automation.
     */
    suspend fun enableAutomation(id: String): GuruAutomation

    /**
     * Disable an automation.
     */
    suspend fun disableAutomation(id: String): GuruAutomation

    /**
     * Delete an automation.
     */
    suspend fun deleteAutomation(id: String)

    // ==================== Automation Execution ====================

    /**
     * Execute an automation by ID.
     */
    suspend fun executeAutomation(id: String, params: Map<String, Any?>): AutomationExecutionResult

    /**
     * Execute an automation by name.
     */
    suspend fun executeAutomationByName(name: String, params: Map<String, Any?>): AutomationExecutionResult

    /**
     * Record automation execution.
     */
    suspend fun recordExecution(id: String)

    // ==================== Summary ====================

    /**
     * Get a summary of automations.
     */
    suspend fun getSummary(): AutomationSummary

    // ==================== Validation ====================

    /**
     * Validate an automation definition.
     */
    suspend fun validateAutomation(request: CreateAutomationRequest): ValidationResult

    /**
     * Check if an automation name is available.
     */
    suspend fun isNameAvailable(name: String): Boolean
}