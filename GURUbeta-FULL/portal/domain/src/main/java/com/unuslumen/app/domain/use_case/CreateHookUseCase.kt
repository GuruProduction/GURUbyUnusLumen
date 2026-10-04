// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.CreateHookRequest
import com.unuslumen.app.domain.model.GuruHook
import com.unuslumen.app.domain.repository.HookRepository
import org.koin.core.annotation.Factory

@Factory
class CreateHookUseCase(
    private val hookRepository: HookRepository
) {
    suspend operator fun invoke(
        name: String,
        displayName: String,
        description: String,
        eventType: String,
        triggerTiming: String,
        conditionJson: String?,
        actionJson: String,
        priority: Int
    ): Result<GuruHook> = runCatching {
        val timing = try {
            com.unuslumen.app.domain.model.TriggerTiming.valueOf(triggerTiming.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid trigger timing: $triggerTiming. Must be BEFORE or AFTER")
        }
        
        val event = try {
            com.unuslumen.app.domain.model.HookEventType.valueOf(eventType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid event type: $eventType")
        }
        
        val condition = conditionJson?.let {
            kotlinx.serialization.json.Json.decodeFromString<com.unuslumen.app.domain.model.HookCondition>(it)
        }
        
        val action = kotlinx.serialization.json.Json.decodeFromString<com.unuslumen.app.domain.model.HookAction>(actionJson)
        
        val request = CreateHookRequest(
            name = name,
            displayName = displayName,
            description = description,
            eventType = event,
            triggerTiming = timing,
            condition = condition,
            action = action,
            priority = priority
        )
        
        val validation = hookRepository.validateHook(request)
        if (!validation.valid) {
            throw IllegalArgumentException(validation.errors.joinToString("; "))
        }
        
        hookRepository.createHook(request)
    }
}