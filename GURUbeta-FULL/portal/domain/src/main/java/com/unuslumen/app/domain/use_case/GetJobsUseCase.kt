package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruJob
import com.unuslumen.app.domain.repository.JobRepository
import org.koin.core.annotation.Factory

@Factory
class GetJobsUseCase(
    private val jobRepository: JobRepository
) {
    suspend operator fun invoke(): Result<List<GuruJob>> = runCatching {
        jobRepository.getAllJobs()
    }
}