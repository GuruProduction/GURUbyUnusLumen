// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.thoughts.domain.use_case

import com.unuslumen.app.thoughts.domain.model.CreateThoughtCycleRequest
import com.unuslumen.app.thoughts.domain.model.GuruThoughtCycle
import com.unuslumen.app.thoughts.domain.repository.ThoughtCycleRepository
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Factory

@Factory
class CreateThoughtCycleUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(
        name: String,
        displayName: String,
        description: String,
        triggerType: String,
        triggerConfig: String,
        thoughtProcessJson: String,
        outputType: String,
        outputConfig: String?
    ): Result<GuruThoughtCycle> = runCatching {
        val type = try {
            com.unuslumen.app.thoughts.domain.model.ThoughtTriggerType.valueOf(triggerType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid trigger type: $triggerType")
        }
        
        val output = try {
            com.unuslumen.app.thoughts.domain.model.ThoughtOutputType.valueOf(outputType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid output type: $outputType")
        }
        
        val process = try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val array = json.parseToJsonElement(thoughtProcessJson).jsonArray
            array.map { element ->
                val obj = element.jsonObject
                com.unuslumen.app.thoughts.domain.model.ThoughtStep(
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    input = obj["input"]?.jsonPrimitive?.content ?: "",
                    params = obj["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                )
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid thought process JSON: ${e.message}")
        }
        
        val request = CreateThoughtCycleRequest(
            name = name,
            displayName = displayName,
            description = description,
            triggerType = type,
            triggerConfig = triggerConfig,
            thoughtProcess = process,
            outputType = output,
            outputConfig = outputConfig
        )
        
        val validation = thoughtCycleRepository.validateCycle(request)
        if (!validation.valid) {
            throw IllegalArgumentException(validation.errors.joinToString("; "))
        }
        
        thoughtCycleRepository.createCycle(request)
    }
}