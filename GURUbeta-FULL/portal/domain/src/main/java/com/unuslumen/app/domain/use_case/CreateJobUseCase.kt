// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.CreateJobRequest
import com.unuslumen.app.domain.model.GuruJob
import com.unuslumen.app.domain.repository.JobRepository
import org.koin.core.annotation.Factory

@Factory
class CreateJobUseCase(
    private val jobRepository: JobRepository
) {
    suspend operator fun invoke(
        name: String,
        displayName: String,
        description: String,
        scheduleType: String,
        scheduleConfig: String,
        actionType: String,
        actionTarget: String,
        actionParams: Map<String, String>,
        input: Map<String, Any?>?
    ): Result<GuruJob> = runCatching {
        val type = try {
            com.unuslumen.app.domain.model.ScheduleType.valueOf(scheduleType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid schedule type: $scheduleType")
        }
        
        val action = com.unuslumen.app.domain.model.JobAction(
            type = actionType,
            target = actionTarget,
            params = actionParams
        )
        
        val request = CreateJobRequest(
            name = name,
            displayName = displayName,
            description = description,
            scheduleType = type,
            scheduleConfig = scheduleConfig,
            action = action,
            input = input
        )
        
        val validation = jobRepository.validateJob(request)
        if (!validation.valid) {
            throw IllegalArgumentException(validation.errors.joinToString("; "))
        }
        
        jobRepository.createJob(request)
    }
}