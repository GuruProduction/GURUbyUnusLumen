package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import org.koin.core.annotation.Factory

@Factory
class AcknowledgeInsightUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(insightId: String): Result<Unit> = runCatching {
        thoughtCycleRepository.acknowledgeInsight(insightId)
    }
}