// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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