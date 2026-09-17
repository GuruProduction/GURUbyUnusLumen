package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface ThoughtCycleRepository {
    
    /**
     * Create a new thought cycle.
     */
    suspend fun createCycle(request: CreateThoughtCycleRequest): GuruThoughtCycle
    
    /**
     * Get all thought cycles.
     */
    suspend fun getAllCycles(): List<GuruThoughtCycle>
    
    /**
     * Get all thought cycles as a flow.
     */
    fun getAllCyclesFlow(): Flow<List<GuruThoughtCycle>>
    
    /**
     * Get enabled thought cycles.
     */
    suspend fun getEnabledCycles(): List<GuruThoughtCycle>
    
    /**
     * Get enabled thought cycles as a flow.
     */
    fun getEnabledCyclesFlow(): Flow<List<GuruThoughtCycle>>
    
    /**
     * Get a thought cycle by ID.
     */
    suspend fun getCycle(id: String): GuruThoughtCycle?
    
    /**
     * Get a thought cycle by name.
     */
    suspend fun getCycleByName(name: String): GuruThoughtCycle?
    
    /**
     * Update a thought cycle.
     */
    suspend fun updateCycle(id: String, request: CreateThoughtCycleRequest): GuruThoughtCycle
    
    /**
     * Enable a thought cycle.
     */
    suspend fun enableCycle(id: String): GuruThoughtCycle
    
    /**
     * Disable a thought cycle.
     */
    suspend fun disableCycle(id: String): GuruThoughtCycle
    
    /**
     * Delete a thought cycle.
     */
    suspend fun deleteCycle(id: String)
    
    /**
     * Execute a thought cycle.
     */
    suspend fun executeCycle(id: String): ThoughtCycleResult
    
    /**
     * Record a cycle run.
     */
    suspend fun recordRun(id: String, result: String?)
    
    // Insights
    
    /**
     * Get all insights.
     */
    suspend fun getAllInsights(): List<GuruInsight>
    
    /**
     * Get all insights as a flow.
     */
    fun getAllInsightsFlow(): Flow<List<GuruInsight>>
    
    /**
     * Get insights by cycle.
     */
    suspend fun getInsightsByCycle(cycleId: String): List<GuruInsight>
    
    /**
     * Get insights by type.
     */
    suspend fun getInsightsByType(type: InsightType): List<GuruInsight>
    
    /**
     * Get unacknowledged insights.
     */
    suspend fun getUnacknowledgedInsights(): List<GuruInsight>
    
    /**
     * Get actionable insights.
     */
    suspend fun getActionableInsights(): List<GuruInsight>
    
    /**
     * Create an insight.
     */
    suspend fun createInsight(insight: GuruInsight): GuruInsight
    
    /**
     * Acknowledge an insight.
     */
    suspend fun acknowledgeInsight(id: String)
    
    /**
     * Dismiss an insight.
     */
    suspend fun dismissInsight(id: String)
    
    /**
     * Mark an insight as action taken.
     */
    suspend fun markInsightActionTaken(id: String, actionType: String, actionId: String)
    
    /**
     * Delete an insight.
     */
    suspend fun deleteInsight(id: String)
    
    /**
     * Get summary statistics.
     */
    suspend fun getSummary(): ThoughtCyclesSummary
    
    /**
     * Validate a thought cycle definition.
     */
    suspend fun validateCycle(request: CreateThoughtCycleRequest): ValidationResult
}