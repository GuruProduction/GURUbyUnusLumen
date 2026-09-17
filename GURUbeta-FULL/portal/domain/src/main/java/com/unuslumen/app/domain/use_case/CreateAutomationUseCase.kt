package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.CreateAutomationRequest
import com.unuslumen.app.domain.model.GuruAutomation
import com.unuslumen.app.domain.repository.AutomationRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Factory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Create a new automation.
 */
@Factory
class CreateAutomationUseCase(
    private val automationRepository: AutomationRepository
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        name: String,
        displayName: String,
        description: String,
        trigger: String,
        triggerConfig: String?,
        stepsJson: String
    ): Result<GuruAutomation> = runCatching {
        // Validate name format
        val sanitizedName = name.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("^_+|_+$"), "")

        if (sanitizedName.isBlank()) {
            throw IllegalArgumentException("Automation name cannot be empty")
        }

        // Check if name is available
        if (!automationRepository.isNameAvailable(sanitizedName)) {
            throw IllegalArgumentException("Automation name '$sanitizedName' is already taken")
        }

        // Parse trigger
        val automationTrigger = try {
            com.unuslumen.app.domain.model.AutomationTrigger.valueOf(trigger.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid trigger type: $trigger. Must be MANUAL, SCHEDULED, or EVENT")
        }

        // Parse steps
        val steps = parseSteps(stepsJson)

        val request = CreateAutomationRequest(
            name = sanitizedName,
            displayName = displayName,
            description = description,
            trigger = automationTrigger,
            triggerConfig = triggerConfig,
            steps = steps
        )

        // Validate
        val validation = automationRepository.validateAutomation(request)
        if (!validation.valid) {
            throw IllegalArgumentException(validation.errors.joinToString("; "))
        }

        automationRepository.createAutomation(request)
    }

    private fun parseSteps(json: String): List<com.unuslumen.app.domain.model.AutomationStep> {
        val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(json)
        return jsonElement.jsonArray.map { stepElement ->
            val stepObj = stepElement.jsonObject
            com.unuslumen.app.domain.model.AutomationStep(
                tool = stepObj["tool"]?.jsonPrimitive?.content ?: "",
                params = stepObj["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                output = stepObj["output"]?.jsonPrimitive?.content ?: ""
            )
        }
    }
}
