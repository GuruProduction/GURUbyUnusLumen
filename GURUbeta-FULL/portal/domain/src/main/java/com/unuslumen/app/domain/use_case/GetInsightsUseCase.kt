package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruInsight
import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import org.koin.core.annotation.Factory

@Factory
class GetInsightsUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(): Result<List<GuruInsight>> = runCatching {
        thoughtCycleRepository.getAllInsights()
    }
}