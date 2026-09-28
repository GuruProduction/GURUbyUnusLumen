package com.unuslumen.app.thoughts.domain.use_case

import com.unuslumen.app.thoughts.domain.model.ThoughtCycleResult
import com.unuslumen.app.thoughts.domain.repository.ThoughtCycleRepository
import org.koin.core.annotation.Factory

@Factory
class ExecuteThoughtCycleUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(cycleId: String): Result<ThoughtCycleResult> = runCatching {
        thoughtCycleRepository.executeCycle(cycleId)
    }
}