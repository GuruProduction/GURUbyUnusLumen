package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.ThoughtCycleResult
import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import org.koin.core.annotation.Factory

@Factory
class ExecuteThoughtCycleUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(cycleId: String): Result<ThoughtCycleResult> = runCatching {
        thoughtCycleRepository.executeCycle(cycleId)
    }
}