package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.JobExecutionResult
import com.unuslumen.app.domain.repository.JobRepository
import org.koin.core.annotation.Factory

@Factory
class ExecuteJobUseCase(
    private val jobRepository: JobRepository
) {
    suspend operator fun invoke(jobId: String): Result<JobExecutionResult> = runCatching {
        jobRepository.executeJob(jobId)
    }
}