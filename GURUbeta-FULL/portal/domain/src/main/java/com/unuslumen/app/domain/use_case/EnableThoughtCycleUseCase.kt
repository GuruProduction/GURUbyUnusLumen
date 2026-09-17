package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruThoughtCycle
import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import org.koin.core.annotation.Factory

@Factory
class EnableThoughtCycleUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(cycleId: String): Result<GuruThoughtCycle> = runCatching {
        thoughtCycleRepository.enableCycle(cycleId)
    }
}