package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruJob
import com.unuslumen.app.domain.repository.JobRepository
import org.koin.core.annotation.Factory

@Factory
class DisableJobUseCase(
    private val jobRepository: JobRepository
) {
    suspend operator fun invoke(jobId: String): Result<GuruJob> = runCatching {
        jobRepository.disableJob(jobId)
    }
}