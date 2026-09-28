package com.unuslumen.app.data.thoughts

import com.unuslumen.app.database.dao.GuruThoughtCycleDao
import com.unuslumen.app.database.dao.GuruInsightDao
import com.unuslumen.app.database.entity.GuruThoughtCycleEntity
import com.unuslumen.app.database.entity.GuruInsightEntity
import com.unuslumen.app.domain.model.*
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import com.unuslumen.app.domain.repository.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [ThoughtCycleRepository::class])
class ThoughtCycleRepositoryImpl(
    private val cycleDao: GuruThoughtCycleDao,
    private val insightDao: GuruInsightDao,
    private val aiRepository: AiRepository,
    private val memoryRepository: MemoryRepository
) : ThoughtCycleRepository {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Wire the EVENT cycle dispatcher: every real app event flowing through
        // HookEventBus now resolves and runs matching EVENT-enabled cycles.
        com.unuslumen.app.data.hooks.HookEventBus.eventCycleDispatcher = { eventType, _ ->
            val matches = EventBridge.cycleIdsForEvent(this, eventType, json)
            if (matches.isNotEmpty()) {
                android.util.Log.d(
                    "guru_thoughts",
                    "Event $eventType matched ${matches.size} cycle(s): ${matches.joinToString { it.second }}"
                )
                matches.forEach { (cycleId, cycleName) ->
                    try {
                        executeCycle(cycleId)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "guru_thoughts",
                            "EVENT cycle '$cycleName' run failed: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    override suspend fun createCycle(request: CreateThoughtCycleRequest): GuruThoughtCycle = withContext(Dispatchers.IO) {
        val cycle = GuruThoughtCycleEntity(
            id = Uuid.random().toString(),
            name = request.name,
            displayName = request.displayName,
            description = request.description,
            triggerType = request.triggerType.name,
            triggerConfig = request.triggerConfig,
            thoughtProcess = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ThoughtStep.serializer()), request.thoughtProcess),
            outputType = request.outputType.name,
            outputConfig = request.outputConfig,
            enabled = true,
            createdAt = System.currentTimeMillis()
        )
        cycleDao.insertCycle(cycle)
        cycle.toDomain()
    }

    override suspend fun getAllCycles(): List<GuruThoughtCycle> = withContext(Dispatchers.IO) {
        cycleDao.getAllCycles().map { it.toDomain() }
    }

    override fun getAllCyclesFlow(): Flow<List<GuruThoughtCycle>> =
        cycleDao.getAllCyclesFlow().map { cycles -> cycles.map { it.toDomain() } }

    override suspend fun getEnabledCycles(): List<GuruThoughtCycle> = withContext(Dispatchers.IO) {
        cycleDao.getEnabledCycles().map { it.toDomain() }
    }

    override fun getEnabledCyclesFlow(): Flow<List<GuruThoughtCycle>> =
        cycleDao.getEnabledCyclesFlow().map { cycles -> cycles.map { it.toDomain() } }

    override suspend fun getCycle(id: String): GuruThoughtCycle? = withContext(Dispatchers.IO) {
        cycleDao.getCycleById(id)?.toDomain()
    }

    override suspend fun getCycleByName(name: String): GuruThoughtCycle? = withContext(Dispatchers.IO) {
        cycleDao.getCycleByName(name)?.toDomain()
    }

    override suspend fun updateCycle(id: String, request: CreateThoughtCycleRequest): GuruThoughtCycle = withContext(Dispatchers.IO) {
        val existing = cycleDao.getCycleById(id) ?: throw IllegalArgumentException("Cycle not found: $id")
        val updated = existing.copy(
            displayName = request.displayName,
            description = request.description,
            triggerType = request.triggerType.name,
            triggerConfig = request.triggerConfig,
            thoughtProcess = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ThoughtStep.serializer()), request.thoughtProcess),
            outputType = request.outputType.name,
            outputConfig = request.outputConfig
        )
        cycleDao.updateCycle(updated)
        updated.toDomain()
    }

    override suspend fun enableCycle(id: String): GuruThoughtCycle = withContext(Dispatchers.IO) {
        cycleDao.setEnabled(id, true)
        cycleDao.getCycleById(id)!!.toDomain()
    }

    override suspend fun disableCycle(id: String): GuruThoughtCycle = withContext(Dispatchers.IO) {
        cycleDao.setEnabled(id, false)
        cycleDao.getCycleById(id)!!.toDomain()
    }

    override suspend fun deleteCycle(id: String) = withContext(Dispatchers.IO) {
        cycleDao.deleteCycleById(id)
    }

    override suspend fun executeCycle(id: String): ThoughtCycleResult {
        val cycle = getCycle(id) ?: throw IllegalArgumentException("Cycle not found: $id")

        if (!cycle.enabled) {
            throw IllegalStateException("Thought cycle '${cycle.name}' is disabled")
        }

        val startTime = System.currentTimeMillis()
        val insights = mutableListOf<GuruInsight>()
        val actions = mutableListOf<String>()
        val proposals = mutableListOf<String>()

        try {
            // Execute each thought step through the live LLM (runStepThroughLlm
            // builds the step prompt with memory context and parses the strict-JSON
            // response). Cycle identity is stamped onto every parsed insight BEFORE
            // persistence so getInsightsByCycle always links records to their cycle.
            for (step in cycle.thoughtProcess) {
                val stepResult = runStepThroughLlm(step, cycle)

                when (step.type) {
                    "ANALYZE", "REFLECT" -> stepResult.insights.forEach { insight ->
                        val created = createInsight(
                            insight.copy(cycleId = cycle.id, cycleName = cycle.name)
                        )
                        insights.add(created)
                        cycleDao.incrementInsightCount(id)
                    }
                    else -> {}
                }
                when (step.type) {
                    "SYNTHESIZE" -> stepResult.actions.forEach {
                        cycleDao.incrementActionCount(id)
                    }
                    "REFLECT" -> stepResult.actions.forEach {
                        cycleDao.incrementActionCount(id)
                    }
                    else -> {}
                }
                when (step.type) {
                    "PROPOSE" -> stepResult.proposals.forEach {
                        cycleDao.incrementProposalCount(id)
                    }
                    else -> {}
                }
                actions.addAll(stepResult.actions)
                proposals.addAll(stepResult.proposals)
            }

            // Output-type routing: the cycle's declared outputType decides where
            // the run's results LAND, on top of the per-step persistence above.
            routeOutputs(cycle, insights, actions, proposals)

            val executionTime = System.currentTimeMillis() - startTime
            recordRun(id, "Success: ${insights.size} insights, ${actions.size} actions, ${proposals.size} proposals")
            
            return ThoughtCycleResult(
                cycleId = id,
                cycleName = cycle.name,
                startedAt = startTime,
                completedAt = System.currentTimeMillis(),
                success = true,
                insights = insights,
                actions = actions,
                proposals = proposals,
                error = null,
                executionTimeMs = executionTime
            )
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            recordRun(id, "Error: ${e.message}")
            
            return ThoughtCycleResult(
                cycleId = id,
                cycleName = cycle.name,
                startedAt = startTime,
                completedAt = System.currentTimeMillis(),
                success = false,
                insights = emptyList(),
                actions = emptyList(),
                proposals = emptyList(),
                error = e.message,
                executionTimeMs = executionTime
            )
        }
    }

    override suspend fun recordRun(id: String, result: String?) = withContext(Dispatchers.IO) {
        cycleDao.recordRun(id, System.currentTimeMillis(), result)
    }

    // Insights

    override suspend fun getAllInsights(): List<GuruInsight> = withContext(Dispatchers.IO) {
        insightDao.getAllInsights().map { it.toDomain() }
    }

    override fun getAllInsightsFlow(): Flow<List<GuruInsight>> =
        insightDao.getAllInsightsFlow().map { insights -> insights.map { it.toDomain() } }

    override suspend fun getInsightsByCycle(cycleId: String): List<GuruInsight> = withContext(Dispatchers.IO) {
        insightDao.getInsightsByCycle(cycleId).map { it.toDomain() }
    }

    override suspend fun getInsightsByType(type: InsightType): List<GuruInsight> = withContext(Dispatchers.IO) {
        insightDao.getInsightsByType(type.name).map { it.toDomain() }
    }

    override suspend fun getUnacknowledgedInsights(): List<GuruInsight> = withContext(Dispatchers.IO) {
        insightDao.getUnacknowledgedInsights().map { it.toDomain() }
    }

    override suspend fun getActionableInsights(): List<GuruInsight> = withContext(Dispatchers.IO) {
        insightDao.getActionableInsights().map { it.toDomain() }
    }

    override suspend fun createInsight(insight: GuruInsight): GuruInsight = withContext(Dispatchers.IO) {
        val entity = GuruInsightEntity(
            id = insight.id,
            cycleId = insight.cycleId,
            type = insight.type.name,
            title = insight.title,
            content = insight.content,
            confidence = insight.confidence,
            source = insight.source,
            actionable = insight.actionable,
            actionTaken = insight.actionTaken,
            actionType = insight.actionType,
            actionId = insight.actionId,
            createdAt = insight.createdAt,
            acknowledgedAt = insight.acknowledgedAt,
            dismissedAt = insight.dismissedAt
        )
        insightDao.insertInsight(entity)
        entity.toDomain()
    }

    override suspend fun acknowledgeInsight(id: String) = withContext(Dispatchers.IO) {
        insightDao.acknowledge(id, System.currentTimeMillis())
    }

    override suspend fun dismissInsight(id: String) = withContext(Dispatchers.IO) {
        insightDao.dismiss(id, System.currentTimeMillis())
    }

    override suspend fun markInsightActionTaken(id: String, actionType: String, actionId: String) = withContext(Dispatchers.IO) {
        insightDao.markActionTaken(id, actionType, actionId)
    }

    override suspend fun deleteInsight(id: String) = withContext(Dispatchers.IO) {
        insightDao.deleteInsightById(id)
    }

    override suspend fun getSummary(): ThoughtCyclesSummary = withContext(Dispatchers.IO) {
        val cycles = cycleDao.getAllCycles()
        val insights = insightDao.getAllInsights()
        
        ThoughtCyclesSummary(
            totalCycles = cycles.size,
            enabledCycles = cycles.count { it.enabled },
            scheduledCycles = cycles.count { it.triggerType == ThoughtTriggerType.SCHEDULED.name },
            eventCycles = cycles.count { it.triggerType == ThoughtTriggerType.EVENT.name },
            thresholdCycles = cycles.count { it.triggerType == ThoughtTriggerType.THRESHOLD.name },
            totalRuns = cycles.sumOf { it.runCount },
            totalInsights = cycles.sumOf { it.insightCount },
            totalActions = cycles.sumOf { it.actionCount },
            totalProposals = cycles.sumOf { it.proposalCount },
            unacknowledgedInsights = insights.count { it.acknowledgedAt == null && it.dismissedAt == null }
        )
    }

    override suspend fun validateCycle(request: CreateThoughtCycleRequest): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate name
        if (request.name.isBlank()) {
            errors.add("Cycle name cannot be empty")
        }
        if (!request.name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            errors.add("Cycle name must start with lowercase letter and contain only lowercase letters, numbers, and underscores")
        }
        
        // Validate thought process
        if (request.thoughtProcess.isEmpty()) {
            errors.add("Thought process must have at least one step")
        }
        
        for ((index, step) in request.thoughtProcess.withIndex()) {
            if (step.type !in listOf("ANALYZE", "REFLECT", "SYNTHESIZE", "PROPOSE")) {
                errors.add("Invalid step type at index $index: ${step.type}. Must be ANALYZE, REFLECT, SYNTHESIZE, or PROPOSE")
            }
            if (step.input.isBlank()) {
                warnings.add("Step at index $index has empty input")
            }
        }
        
        // Validate trigger config based on type
        when (request.triggerType) {
            ThoughtTriggerType.SCHEDULED -> {
                try {
                    json.decodeFromString<ScheduledThoughtConfig>(request.triggerConfig)
                } catch (e: Exception) {
                    errors.add("Invalid scheduled config: ${e.message}")
                }
            }
            ThoughtTriggerType.EVENT -> {
                try {
                    val config = json.decodeFromString<EventThoughtConfig>(request.triggerConfig)
                    if (config.eventTypes.isEmpty()) {
                        errors.add("Event config must specify at least one event type")
                    }
                } catch (e: Exception) {
                    errors.add("Invalid event config: ${e.message}")
                }
            }
            ThoughtTriggerType.THRESHOLD -> {
                try {
                    json.decodeFromString<ThresholdThoughtConfig>(request.triggerConfig)
                } catch (e: Exception) {
                    errors.add("Invalid threshold config: ${e.message}")
                }
            }
        }
        
        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    // Helper methods

    private data class StepResult(
        val insights: List<GuruInsight> = emptyList(),
        val actions: List<String> = emptyList(),
        val proposals: List<String> = emptyList()
    )

    /**
     * Run one thought step through the live LLM.
     */
    private suspend fun runStepThroughLlm(step: ThoughtStep, cycle: GuruThoughtCycle): StepResult {
        val relevantFacts = try {
            memoryRepository.buildContextPreamble(step.input, "", emptyList()).preamble
        } catch (e: Exception) {
            ""
        }

        // The system prompt is the server-fetched master prompt (Steven-authored).
        val stepPrompt = buildString {
            appendLine("Thought step: ")
            appendLine(step.type)
            appendLine("In cycle: ")
            appendLine(cycle.name)
            if (relevantFacts.isNotBlank()) {
                appendLine()
                appendLine("Relevant context from memory:")
                appendLine(relevantFacts)
            }
            appendLine()
            appendLine("Task:")
            appendLine(step.input)
        }

        when (val answer = aiRepository.sendPrompt(stepPrompt)) {
            is PortalResult.Success -> {
                val text = answer.data.trim()
                if (text.isBlank()) return StepResult()
                // Parse the strict-JSON response. parseStepJson is total (never null);
                // the ?: is harmless but silenced here since the compiler flags it.
                val result = parseStepJson(text)
                // Each genuine insight that parses is returned; executeCycle persists them.
                return result
            }
            else -> throw IllegalStateException("LLM error: ${answer::class.simpleName}")
        }
    }

    /**
     * Parse the strict-JSON step response into insights, actions, and proposals.
     */
    private fun parseStepJson(text: String): StepResult {
        var trimmed = text.trim()
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.removePrefix("```json").removePrefix("```")
            trimmed = trimmed.removeSuffix("```")
        }
        val obj = try {
            json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            val sb = trimmed.indexOf('{')
            val eb = trimmed.lastIndexOf('}')
            if (sb < 0 || eb <= sb) return StepResult()
            try { json.parseToJsonElement(trimmed.substring(sb, eb + 1)) } catch (e2: Exception) { return StepResult() }
        }
        if (obj !is kotlinx.serialization.json.JsonObject) return StepResult()

        val insights = mutableListOf<GuruInsight>()
        (obj["insights"] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
            val o = (el as? kotlinx.serialization.json.JsonObject) ?: return@forEach
            val content = o["content"]?.jsonPrimitive?.contentOrNull
            if (content.isNullOrEmpty()) return@forEach
            val type = try {
                InsightType.valueOf((o["type"]?.jsonPrimitive?.contentOrNull ?: "PATTERN").uppercase())
            } catch (e: Exception) {
                InsightType.PATTERN
            }
            insights.add(
                GuruInsight(
                    id = Uuid.random().toString(),
                    cycleId = "",
                    cycleName = "",
                    type = type,
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: content.take(60),
                    content = content,
                    confidence = 0.7f,
                    source = "{}",
                    actionable = o["actionable"]?.jsonPrimitive?.booleanOrNull ?: false,
                    actionTaken = false,
                    actionType = null,
                    actionId = null,
                    createdAt = System.currentTimeMillis(),
                    acknowledgedAt = null,
                    dismissedAt = null
                )
            )
        }
        val actions = (obj["actions"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val proposals = (obj["proposals"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return StepResult(insights = insights, actions = actions, proposals = proposals)
    }

    /**
     * Output-type routing (the declared outputType decides where run results
     * land, on top of the per-step persistence executeCycle already does):
     *
     *  - INSIGHT: nothing extra; insights were persisted per step above.
     *  - MEMORY:  each insight content and proposal becomes a durable memory
     *             fact in the memory store (category "thought_cycle"), source
     *             tagged with the cycle name so HiveMind and context building
     *             pick it up like any other fact.
     *  - PROPOSAL: each proposal text is recorded as an unacknowledged,
     *             dismissable insight record so it surfaces in the existing
     *             insight review UI, marked as a cycle proposal.
     *  - ACTION:  actions are recorded (incrementActionCount above) and made
     *             visible as actionable insights so a human can approve them;
     *             GURU NEVER silently executes device actions from a thought
     *             run. Propose-only semantics, by design.
     */
    private suspend fun routeOutputs(
        cycle: GuruThoughtCycle,
        insights: List<GuruInsight>,
        actions: List<String>,
        proposals: List<String>
    ) {
        when (cycle.outputType) {
            ThoughtOutputType.INSIGHT -> {
                // Already handled per-step.
            }
            ThoughtOutputType.MEMORY -> {
                val facts = buildList {
                    insights.forEach { insight ->
                        add(
                            MemoryFact(
                                id = "tc_${cycle.name}_${insight.id}",
                                category = "thought_cycle",
                                fact = insight.content,
                                confidence = insight.confidence,
                                sourceConversationIds = emptyList(),
                                extractedDate = System.currentTimeMillis(),
                                source = cycle.name
                            )
                        )
                    }
                    proposals.forEach { proposal ->
                        add(
                            MemoryFact(
                                id = "tc_${cycle.name}_prop_${proposal.hashCode()}",
                                category = "thought_cycle",
                                fact = proposal,
                                confidence = 0.7f,
                                sourceConversationIds = emptyList(),
                                extractedDate = System.currentTimeMillis(),
                                source = cycle.name
                            )
                        )
                    }
                }
                if (facts.isNotEmpty()) {
                    try {
                        memoryRepository.persistFacts(facts)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "guru_thoughts",
                            "MEMORY output persistence failed for '${cycle.name}': ${e.message}"
                        )
                    }
                }
            }
            ThoughtOutputType.PROPOSAL -> {
                proposals.forEach { proposal ->
                    createInsight(
                        GuruInsight(
                            id = Uuid.random().toString(),
                            cycleId = cycle.id,
                            cycleName = cycle.name,
                            type = InsightType.SUGGESTION,
                            title = "Proposal from ${cycle.displayName}",
                            content = proposal,
                            confidence = 0.75f,
                            source = "thought_cycle",
                            actionable = false,
                            actionTaken = false,
                            actionType = null,
                            actionId = null,
                            createdAt = System.currentTimeMillis(),
                            acknowledgedAt = null,
                            dismissedAt = null
                        )
                    )
                }
            }
            ThoughtOutputType.ACTION -> {
                // Actions already recorded and counted above; additionally each
                // action text is surfaced as an actionable insight awaiting human
                // approval, so nothing executes silently.
                actions.forEach { action ->
                    createInsight(
                        GuruInsight(
                            id = Uuid.random().toString(),
                            cycleId = cycle.id,
                            cycleName = cycle.name,
                            type = InsightType.SUGGESTION,
                            title = "Proposed action from ${cycle.displayName}",
                            content = action,
                            confidence = 0.75f,
                            source = "thought_cycle",
                            actionable = true,
                            actionTaken = false,
                            actionType = null,
                            actionId = null,
                            createdAt = System.currentTimeMillis(),
                            acknowledgedAt = null,
                            dismissedAt = null
                        )
                    )
                }
            }
        }
    }

    private fun GuruThoughtCycleEntity.toDomain(): GuruThoughtCycle {
        val process = try {
            json.decodeFromString<List<ThoughtStep>>(thoughtProcess)
        } catch (e: Exception) {
            emptyList()
        }
        
        return GuruThoughtCycle(
            id = id,
            name = name,
            displayName = displayName,
            description = description,
            triggerType = ThoughtTriggerType.valueOf(triggerType),
            triggerConfig = triggerConfig,
            thoughtProcess = process,
            outputType = ThoughtOutputType.valueOf(outputType),
            outputConfig = outputConfig,
            enabled = enabled,
            createdAt = createdAt,
            lastRunAt = lastRunAt,
            lastResult = lastResult,
            runCount = runCount,
            insightCount = insightCount,
            actionCount = actionCount,
            proposalCount = proposalCount
        )
    }

    private fun GuruInsightEntity.toDomain(): GuruInsight {
        return GuruInsight(
            id = id,
            cycleId = cycleId,
            cycleName = "", // Would need to join with cycle table
            type = InsightType.valueOf(type),
            title = title,
            content = content,
            confidence = confidence,
            source = source,
            actionable = actionable,
            actionTaken = actionTaken,
            actionType = actionType,
            actionId = actionId,
            createdAt = createdAt,
            acknowledgedAt = acknowledgedAt,
            dismissedAt = dismissedAt
        )
    }
}