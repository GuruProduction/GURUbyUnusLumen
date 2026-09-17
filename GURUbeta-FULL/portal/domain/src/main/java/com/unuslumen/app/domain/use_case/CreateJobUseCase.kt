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