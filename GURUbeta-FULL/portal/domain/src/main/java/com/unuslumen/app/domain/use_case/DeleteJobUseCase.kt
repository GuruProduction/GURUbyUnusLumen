package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.JobRepository
import org.koin.core.annotation.Factory

@Factory
class DeleteJobUseCase(
    private val jobRepository: JobRepository
) {
    suspend operator fun invoke(jobId: String): Result<Unit> = runCatching {
        jobRepository.deleteJob(jobId)
    }
}